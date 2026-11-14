package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.llm.DeepSeekClient;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.CommandSandbox;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Owns run isolation, execution, cancellation, discovery metadata, and snapshots.
 * Each run gets a sibling directory under agent-workspaces/.
 */
public final class AgentService implements AutoCloseable {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_TASK_CHARS = 20_000;
    private static final int MAX_RESTORED_SESSIONS = 200;

    private final AppConfig config;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final ToolRegistry tools;
    private final ExecutorService executor;
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Set<Path> activeWorkspaces = ConcurrentHashMap.newKeySet();
    private final Object startLock = new Object();
    private volatile boolean closed;

    public AgentService(AppConfig config) {
        this(config, new DeepSeekClient(config.llm()));
    }

    public AgentService(AppConfig config, LlmClient llm) {
        this.config = config;
        this.llm = llm;
        this.skills = SkillRegistry.load(config.skillsDir());
        this.tools = BuiltInTools.registry();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Files.createDirectories(config.workspacesDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create workspaces directory " + config.workspacesDir(), e);
        }
        restoreRuns();
        Log.info("agent.service.ready", Map.of(
                "model", config.llm().model(),
                "skills", skills.all().size(),
                "tools", tools.names().size(),
                "apiKeyConfigured", config.hasApiKey()
        ));
    }

    /**
     * Starts a run in a fresh, isolated directory. The optional name is a single
     * path segment under agent-workspaces; omit it to generate a unique one.
     */
    public RunSnapshot start(String task, String workspaceName) {
        if (closed) {
            throw new IllegalStateException("Agent service is shutting down");
        }
        String cleanTask = task == null ? "" : task.strip();
        if (cleanTask.isEmpty()) {
            throw new IllegalArgumentException("Task must not be blank");
        }
        if (cleanTask.length() > MAX_TASK_CHARS) {
            throw new IllegalArgumentException("Task is too long (max " + MAX_TASK_CHARS + " characters)");
        }

        Workspace workspace;
        synchronized (startLock) {
            String name = workspaceName == null || workspaceName.isBlank()
                    ? uniqueName()
                    : validateName(workspaceName);
            Path directory = config.workspacesDir().resolve(name).normalize();
            if (!directory.startsWith(config.workspacesDir())) {
                throw new IllegalArgumentException("Invalid workspace name");
            }
            if (Files.exists(directory)) {
                throw new IllegalArgumentException("Workspace already exists: " + name
                        + " (omit workspace to create a fresh run)");
            }
            workspace = Workspace.open(directory);
            if (!activeWorkspaces.add(workspace.root())) {
                throw new IllegalStateException("Workspace is already used by a running agent");
            }
        }

        String id = "run-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        RunState state = new RunState(id, cleanTask, workspace.root().getFileName().toString(),
                config.agent().maxSteps());
        RunEventLog events = new RunEventLog();
        RunControl control = new RunControl();
        CompletableFuture<RunSnapshot> completion = new CompletableFuture<>();
        AgentRun run = new AgentRun(id, workspace, state, events, control, completion);
        runs.put(id, run);
        state.setFiles(workspace.listFiles(500, 8));

        try {
            executor.submit(() -> {
                control.attach(Thread.currentThread());
                try {
                    RunSnapshot snapshot = new AgentLoop(
                            llm, tools, skills, config.agent(), config.llm().maxOutputTokens()
                    ).run(cleanTask, workspace, state, events, control);
                    completion.complete(snapshot);
                } catch (Throwable t) {
                    if (!state.snapshot().terminal()) {
                        state.fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                    }
                    completion.completeExceptionally(t);
                    Log.error("agent.executor.failed", t, Map.of("runId", id));
                } finally {
                    activeWorkspaces.remove(workspace.root());
                    events.close();
                }
            });
        } catch (RuntimeException e) {
            activeWorkspaces.remove(workspace.root());
            runs.remove(id);
            events.close();
            throw e;
        }
        Log.info("agent.run.queued", Map.of("runId", id, "workspace", workspace.root().getFileName().toString()));
        return state.snapshot();
    }

    public Optional<AgentRun> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(runs.get(id));
    }

    public AgentRun require(String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("Run not found: " + id));
    }

    public List<RunSnapshot> list() {
        List<RunSnapshot> snapshots = new ArrayList<>();
        for (AgentRun run : runs.values()) {
            snapshots.add(run.snapshot());
        }
        snapshots.sort(Comparator.comparingLong(RunSnapshot::startedAt).reversed());
        return snapshots;
    }

    public RunSnapshot cancel(String id) {
        AgentRun run = require(id);
        run.control().cancel();
        return run.snapshot();
    }

    public RunSnapshot await(String id, Duration timeout) {
        AgentRun run = require(id);
        try {
            return run.completion().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for run " + id, e);
        } catch (ExecutionException e) {
            return run.snapshot();
        } catch (TimeoutException e) {
            return run.snapshot();
        }
    }

    public String readFile(String id, String path) {
        return require(id).workspace().readText(path);
    }

    public Map<String, Object> configSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", AppConfig.VERSION);
        summary.put("model", config.llm().model());
        summary.put("baseUrl", config.llm().baseUrl());
        summary.put("apiKeyConfigured", config.hasApiKey());
        summary.put("maxSteps", config.agent().maxSteps());
        summary.put("maxToolCallsPerStep", config.agent().maxToolCallsPerStep());
        summary.put("contextCharBudget", config.agent().contextCharBudget());
        summary.put("commandTimeoutSeconds", config.agent().commandTimeoutSeconds());
        summary.put("autoVerify", config.agent().autoVerify());
        summary.put("workspaceRoot", config.workspacesDir().toString());
        summary.put("skillsDir", config.skillsDir().toString());
        summary.put("toolCount", tools.names().size());
        summary.put("skillCount", skills.all().size());
        summary.put("languages", Language.ids());
        CommandSandbox sandbox = new CommandRunner().sandbox();
        summary.put("sandbox", sandbox.modeName());
        summary.put("sandboxNetwork", sandbox.networkEnabled());
        summary.put("sandboxDescription", sandbox.description());
        return summary;
    }

    public List<Map<String, Object>> toolDescriptions() {
        return tools.describe();
    }

    public List<Map<String, Object>> skillDescriptions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : skills.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", skill.name());
            item.put("description", skill.description());
            item.put("tags", skill.tags());
            item.put("triggers", skill.triggers());
            item.put("path", skill.relativePath());
            result.add(item);
        }
        return result;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public SkillRegistry skills() {
        return skills;
    }

    public AppConfig config() {
        return config;
    }

    /**
     * Rehydrates recent terminal runs from {@code agent-workspaces/<name>/.agent/}.
     * Sessions are read-only history: their executor died with the previous
     * process, so they are not inserted into the active-workspace set.
     */
    private void restoreRuns() {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(config.workspacesDir())) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(".agent/transcript.json")))
                    .forEach(candidates::add);
        } catch (IOException e) {
            Log.warn("agent.sessions.scan_failed", Map.of("error", String.valueOf(e.getMessage())));
            return;
        }
        candidates.sort(Comparator.comparingLong(this::sessionModified).reversed());

        int restored = 0;
        for (Path directory : candidates) {
            if (restored >= MAX_RESTORED_SESSIONS) {
                break;
            }
            try {
                Workspace workspace = Workspace.open(directory);
                RunArtifacts.StoredRun stored = RunArtifacts.load(workspace.root()).orElse(null);
                if (stored == null) {
                    continue;
                }
                RunSnapshot snapshot = stored.snapshot();
                if (runs.containsKey(snapshot.id())) {
                    Log.warn("agent.sessions.duplicate", Map.of("runId", snapshot.id(),
                            "workspace", workspace.root().getFileName().toString()));
                    continue;
                }
                RunState state = RunState.restore(snapshot);
                RunEventLog events = RunEventLog.restore(stored.events());
                AgentRun run = new AgentRun(snapshot.id(), workspace, state, events,
                        new RunControl(), CompletableFuture.completedFuture(state.snapshot()));
                runs.put(snapshot.id(), run);
                restored++;
            } catch (RuntimeException e) {
                Log.warn("agent.sessions.restore_failed", Map.of(
                        "workspace", directory.getFileName().toString(),
                        "error", String.valueOf(e.getMessage())));
            }
        }
        Log.info("agent.sessions.restored", Map.of(
                "restored", restored,
                "candidates", candidates.size()));
    }

    private long sessionModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory.resolve(".agent/transcript.json")).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private String uniqueName() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String suffix = java.util.UUID.randomUUID().toString().substring(0, 6);
            String candidate = "run-" + LocalDateTime.now().format(STAMP) + "-" + suffix;
            if (!Files.exists(config.workspacesDir().resolve(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique workspace name");
    }

    private static String validateName(String raw) {
        String name = raw.strip();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}") || name.contains("..")) {
            throw new IllegalArgumentException(
                    "Workspace name must be one segment of letters, digits, dot, dash or underscore");
        }
        return name;
    }

    @Override
    public void close() {
        closed = true;
        runs.values().forEach(run -> run.control().cancel());
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.info("agent.service.closed", Map.of("runs", runs.size()));
    }
}
