package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.history.EventStore;
import io.github.jasoncai0.agent.history.MySqlEventStore;
import io.github.jasoncai0.agent.history.SessionArchiver;
import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.DeepSeekClient;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.mcp.McpManager;
import io.github.jasoncai0.agent.memory.MemoryScope;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.MemoryServices;
import io.github.jasoncai0.agent.memory.NoopMemoryService;
import io.github.jasoncai0.agent.memory.admission.MemoryAdmissionPipeline;
import io.github.jasoncai0.agent.memory.admission.PendingMemoryItem;
import io.github.jasoncai0.agent.memory.admission.PendingMemoryStore;
import io.github.jasoncai0.agent.memory.admission.PendingMemoryStores;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionScope;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.question.QuestionAnswer;
import io.github.jasoncai0.agent.question.QuestionBroker;
import io.github.jasoncai0.agent.question.QuestionRequest;
import io.github.jasoncai0.agent.sandbox.SandboxRegistry;
import io.github.jasoncai0.agent.sandbox.SandboxSession;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.team.TeamMember;
import io.github.jasoncai0.agent.team.TeamService;
import io.github.jasoncai0.agent.team.TeamStores;
import io.github.jasoncai0.agent.team.TeamTask;
import io.github.jasoncai0.agent.team.TeamToolset;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.tool.builtin.DelegateTaskTool;
import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.CommandSandbox;
import io.github.jasoncai0.agent.workspace.Workspace;
import io.github.jasoncai0.agent.workspace.WorkspaceFork;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns run isolation, execution, cancellation, discovery metadata, and snapshots.
 * Session recovery/rebasing and retention/archival live in {@link SessionRecoveryService}
 * and {@link SessionArchiver}.
 */
public final class AgentService implements AutoCloseable {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_TASK_CHARS = 20_000;
    private static final int MAX_MESSAGE_CHARS = 20_000;

    private final AppConfig config;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final ToolRegistry tools;
    private final McpManager mcp;
    private final MemoryService memory;
    private final PendingMemoryStore pendingMemories;
    private final MemoryAdmissionPipeline memoryPipeline;
    private final SandboxRegistry sandboxRegistry;
    private final TeamService team;
    private final ExecutorService executor;
    private final EventStore eventStore;
    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    private final Set<Path> activeWorkspaces = ConcurrentHashMap.newKeySet();
    private final Object startLock = new Object();
    private volatile boolean closed;
    private volatile SubagentService subagents;

    public AgentService(AppConfig config) {
        this(config, new DeepSeekClient(config.llm()), createStore(config));
    }

    public AgentService(AppConfig config, LlmClient llm) {
        this(config, llm, null);
    }

    public AgentService(AppConfig config, LlmClient llm, EventStore eventStore) {
        this.config = config;
        this.llm = llm;
        this.eventStore = eventStore;
        this.skills = SkillRegistry.load(config.skillsDir());
        this.tools = BuiltInTools.registry();
        this.mcp = McpManager.connect(config.projectRoot(), this.tools);
        this.sandboxRegistry = new SandboxRegistry();
        this.sandboxRegistry.validateStartup(config.sandbox());
        this.memory = MemoryServices.create(config.memory(), config.workspacesDir());
        this.pendingMemories = PendingMemoryStores.create(config.mysql());
        this.memoryPipeline = new MemoryAdmissionPipeline(this.memory, config.memory(), llm, this.pendingMemories);
        this.team = new TeamService(this, TeamStores.create(config.mysql()), config.teamDefault());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Files.createDirectories(config.workspacesDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create workspaces directory " + config.workspacesDir(), e);
        }
        SessionRecoveryService.restoreAll(config, eventStore, runs);
        Log.info("agent.service.ready", Map.of(
                "model", config.llm().model(),
                "skills", skills.all().size(),
                "tools", tools.names().size(),
                "memory", memory.health().available(),
                "apiKeyConfigured", config.hasApiKey()
        ));
    }

    public RunSnapshot start(String task, String workspaceName) {
        return start(task, workspaceName, false);
    }

    public RunSnapshot start(String task, String workspaceName, boolean teamEnabled) {
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

        String id = "run-" + UUID.randomUUID().toString().substring(0, 8);
        RunState state = new RunState(id, cleanTask, workspace.root().getFileName().toString(),
                config.agent().maxSteps());
        RunEventLog events = new RunEventLog(id, eventStore);
        RunControl control = new RunControl();
        CompletableFuture<RunSnapshot> completion = new CompletableFuture<>();
        AgentRun run = new AgentRun(id, workspace, state, events);
        if (!run.beginTurn(control, completion)) {
            activeWorkspaces.remove(workspace.root());
            throw new IllegalStateException("Could not start agent turn");
        }
        runs.put(id, run);
        team.ensureRoot(id);
        if (teamEnabled) {
            team.setEnabled(run, true);
        }
        state.setFiles(workspace.listFiles(500, 8));
        if (eventStore != null) {
            eventStore.saveSnapshot(state.snapshot());
        }

        try {
            executor.submit(() -> executeTurn(run, control, completion, cleanTask, false));
        } catch (RuntimeException e) {
            activeWorkspaces.remove(workspace.root());
            runs.remove(id);
            run.finishTurn();
            events.close();
            throw e;
        }
        Log.info("agent.run.queued", Map.of("runId", id, "workspace", workspace.root().getFileName().toString()));
        return state.snapshot();
    }

    public RunSnapshot followUp(String id, String message) {
        if (closed) {
            throw new IllegalStateException("Agent service is shutting down");
        }
        String clean = message == null ? "" : message.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
        if (clean.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("Message is too long (max " + MAX_MESSAGE_CHARS + " characters)");
        }
        AgentRun run = require(id);
        if (run.running() || !run.snapshot().terminal()) {
            throw new IllegalStateException("Session is still running; wait for it to finish");
        }
        Path root = run.workspace().root();
        synchronized (startLock) {
            if (!activeWorkspaces.add(root)) {
                throw new IllegalStateException("Workspace is already used by a running agent");
            }
        }
        RunControl control = new RunControl();
        CompletableFuture<RunSnapshot> completion = new CompletableFuture<>();
        synchronized (run) {
            if (!run.beginTurn(control, completion)) {
                activeWorkspaces.remove(root);
                throw new IllegalStateException("Session is still running; wait for it to finish");
            }
            run.state().beginTurn();
            run.events().open();
            if (eventStore != null) {
                eventStore.saveSnapshot(run.state().snapshot());
            }
        }
        try {
            executor.submit(() -> executeTurn(run, control, completion, clean, true));
        } catch (RuntimeException e) {
            activeWorkspaces.remove(root);
            run.finishTurn();
            run.events().close();
            throw e;
        }
        Log.info("agent.turn.queued", Map.of("runId", id, "messageChars", clean.length()));
        return run.snapshot();
    }

    public record ForkResult(String runId, String parentRunId, String workspace) {
    }

    public ForkResult fork(String parentId, String name) {
        if (closed) {
            throw new IllegalStateException("Agent service is shutting down");
        }
        return SessionForkService.fork(require(parentId), name, config.workspacesDir(),
                startLock, eventStore, runs, team);
    }

    public SessionArchiver.PruneResult pruneSessions(int keepCount, int maxAgeDays, boolean dryRun) {
        return pruneSessions(keepCount, maxAgeDays, dryRun, System.currentTimeMillis());
    }

    SessionArchiver.PruneResult pruneSessions(int keepCount, int maxAgeDays, boolean dryRun, long nowMillis) {
        Set<String> active = new HashSet<>();
        for (AgentRun run : runs.values()) {
            if (run.running() || !run.snapshot().terminal()) {
                active.add(run.id());
            }
        }
        SessionArchiver.PruneResult result = SessionArchiver.prune(
                config.workspacesDir(), list(), active, eventStore,
                new SessionArchiver.Policy(keepCount, maxAgeDays, dryRun), nowMillis);
        if (!dryRun && !result.prunedRunIds().isEmpty()) {
            Set<String> removedWorkspaces = new HashSet<>();
            for (String prunedId : result.prunedRunIds()) {
                AgentRun removed = runs.remove(prunedId);
                if (removed != null) {
                    removed.closeSandbox();
                    if (removed.state().workspace() != null) {
                        removedWorkspaces.add(removed.state().workspace());
                    }
                }
            }
            if (!removedWorkspaces.isEmpty()) {
                runs.entrySet().removeIf(entry -> {
                    boolean match = (entry.getKey().startsWith("sub-") || entry.getKey().startsWith("team-"))
                            && removedWorkspaces.contains(entry.getValue().state().workspace());
                    if (match) {
                        entry.getValue().closeSandbox();
                    }
                    return match;
                });
            }
        }
        return result;
    }

    private SubagentService subagents() {
        SubagentService current = subagents;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (subagents == null) {
                subagents = new SubagentService(this);
            }
            return subagents;
        }
    }

    private ToolRegistry toolsForRun(AgentRun run) {
        if (team.isMember(run)) {
            ToolRegistry registry = new ToolRegistry();
            for (Tool tool : BuiltInTools.all()) {
                if (!tool.spec().name().equals("ask_user")) {
                    registry.register(tool);
                }
            }
            TeamToolset.register(registry, team, run);
            return registry;
        }
        if (run.state().isSubagent()) {
            return subagentTools();
        }
        ToolRegistry registry = new ToolRegistry().registerAll(tools.all());
        registry.register(new DelegateTaskTool(subagents(), run));
        if (team.enabledFor(run)) {
            TeamToolset.register(registry, team, run);
        }
        return registry;
    }

    private static ToolRegistry subagentTools() {
        ToolRegistry registry = new ToolRegistry();
        for (Tool tool : BuiltInTools.all()) {
            if (SubagentService.readOnlyToolNames().contains(tool.spec().name())) {
                registry.register(tool);
            }
        }
        return registry;
    }

    AgentRun newChildRun(AgentRun parent, String name, String task) {
        String id = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = Workspace.open(parent.workspace().root());
        RunState state = new RunState(id, task, parent.state().workspace(), 40);
        state.markSubagent(parent.id());
        RunEventLog events = new RunEventLog(id, eventStore);
        AgentRun child = new AgentRun(id, workspace, state, events);
        runs.put(id, child);
        Log.info("subagent.created", Map.of(
                "parentRunId", parent.id(),
                "childRunId", id,
                "name", name == null || name.isBlank() ? "subagent" : name));
        return child;
    }

    void runChild(AgentRun child, String task, RunControl control,
                  CompletableFuture<RunSnapshot> completion) {
        if (!child.beginTurn(control, completion)) {
            completion.completeExceptionally(new IllegalStateException("Child already running"));
            return;
        }
        control.attach(Thread.currentThread());
        try {
            AgentLoop loop = new AgentLoop(
                    llm, subagentTools(), skills, childSettings(), config.llm().maxOutputTokens(),
                    verifierForRun(child), new PermissionBroker(), new PermissionSession(),
                    new QuestionBroker(), GoalReviewer.NOOP)
                    .withLiveDeltas(child.deltas());
            loop.run(task, child.workspace(), child.state(), child.events(), control);
            completion.complete(child.state().snapshot());
        } catch (Throwable t) {
            if (!child.state().snapshot().terminal()) {
                if (t instanceof RunControl.RunCancelledException || control.isCancelled()) {
                    child.state().cancel();
                } else {
                    child.state().fail(t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage());
                }
            }
            completion.completeExceptionally(t);
        } finally {
            if (eventStore != null) {
                try {
                    eventStore.saveSnapshot(child.state().snapshot());
                } catch (RuntimeException e) {
                    Log.error("agent.snapshot.persist_failed", e, Map.of("runId", child.id()));
                }
            }
            child.events().close();
            child.finishTurn();
        }
    }

    private AgentSettings childSettings() {
        AgentSettings base = config.agent();
        return new AgentSettings(40, 8, base.contextCharBudget(), base.commandTimeoutSeconds(),
                false, PermissionMode.READ_ONLY, 0, false, 30_000, true, false);
    }

    public AgentRun newTeamRun(AgentRun parent, String rootRunId, String name, String task) {
        String id = "team-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace workspace = Workspace.open(parent.workspace().root());
        RunState state = new RunState(id, task, parent.state().workspace(), config.agent().maxSteps());
        state.markSubagent(parent.id());
        RunEventLog events = new RunEventLog(id, eventStore);
        AgentRun child = new AgentRun(id, workspace, state, events);
        runs.put(id, child);
        state.setFiles(workspace.listFiles(500, 8));
        if (eventStore != null) {
            eventStore.saveSnapshot(state.snapshot());
        }
        Log.info("team.member.created", Map.of("rootRunId", rootRunId, "memberId", id, "name", name));
        return child;
    }

    public RunSnapshot runTeamTurn(TeamMember member, String userMessage, boolean continuation) {
        AgentRun run = member.run();
        CompletableFuture<RunSnapshot> completion = new CompletableFuture<>();
        RunControl control = new RunControl();
        if (!run.beginTurn(control, completion)) {
            throw new IllegalStateException("team member is already running");
        }
        control.attach(Thread.currentThread());
        try {
            AgentSettings base = config.agent();
            AgentSettings teamSettings = new AgentSettings(
                    base.maxSteps(), base.maxToolCallsPerStep(), base.contextCharBudget(),
                    base.commandTimeoutSeconds(), base.autoVerify(), base.permissionMode(),
                    base.tokenBudget(), false, base.contextTokenBudget(),
                    base.overflowRecovery(), base.preloadSkills());
            AgentLoop loop = new AgentLoop(
                    llm, toolsForRun(run), skills, teamSettings, config.llm().maxOutputTokens(),
                    verifierForRun(run), run.permissions(), run.permissionSession(), run.questions(),
                    GoalReviewer.NOOP, NoopMemoryService.off())
                    .withLiveDeltas(run.deltas())
                    .withTeamPolicy(team.teamPolicy(run));
            RunSnapshot snapshot = continuation
                    ? loop.continueSession(userMessage, RunArtifacts.loadMessages(run.workspace().root(), run.id()),
                            run.workspace(), run.state(), run.events(), control)
                    : loop.run(userMessage, run.workspace(), run.state(), run.events(), control);
            completion.complete(snapshot);
            return snapshot;
        } catch (Throwable t) {
            if (!run.state().snapshot().terminal()) {
                run.state().fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            completion.completeExceptionally(t);
            throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
        } finally {
            if (eventStore != null) {
                try {
                    eventStore.saveSnapshot(run.state().snapshot());
                } catch (RuntimeException e) {
                    Log.error("team.snapshot.persist_failed", e, Map.of("runId", run.id()));
                }
            }
            run.finishTurn();
        }
    }

    private void executeTurn(AgentRun run, RunControl control,
                             CompletableFuture<RunSnapshot> completion,
                             String userMessage, boolean continuation) {
        Workspace workspace = run.workspace();
        RunState state = run.state();
        RunEventLog events = run.events();
        control.attach(Thread.currentThread());
        try {
            AgentLoop loop = new AgentLoop(
                    llm, toolsForRun(run), skills, config.agent(), config.llm().maxOutputTokens(),
                    verifierForRun(run), run.permissions(), run.permissionSession(), run.questions(),
                    new LlmGoalReviewer(llm), memory)
                    .withMemoryPipeline(memoryPipeline)
                    .withLiveDeltas(run.deltas())
                    .withTeamPolicy(team.teamPolicy(run));
            RunSnapshot snapshot = continuation
                    ? loop.continueSession(userMessage, RunArtifacts.loadMessages(workspace.root()),
                            workspace, state, events, control)
                    : loop.run(userMessage, workspace, state, events, control);
            completion.complete(snapshot);
        } catch (Throwable t) {
            if (!state.snapshot().terminal()) {
                state.fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            completion.completeExceptionally(t);
            Log.error("agent.executor.failed", t, Map.of("runId", run.id()));
        } finally {
            if (eventStore != null) {
                try {
                    eventStore.saveSnapshot(state.snapshot());
                } catch (RuntimeException e) {
                    Log.error("agent.snapshot.persist_failed", e, Map.of("runId", run.id()));
                }
            }
            run.finishTurn();
            activeWorkspaces.remove(workspace.root());
        }
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
        SubagentService children = subagents;
        if (children != null) {
            children.cancelChildren(id);
        }
        team.cancelRoot(id);
        run.permissions().cancelAll("run cancelled");
        run.questions().cancelAll("run cancelled");
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
        } catch (ExecutionException | TimeoutException e) {
            return run.snapshot();
        }
    }

    public String readFile(String id, String path) {
        return require(id).workspace().readText(path);
    }

    public PermissionRequest pendingPermission(String id) {
        return require(id).permissions().pendingFor(id);
    }

    public QuestionRequest pendingQuestion(String id) {
        return require(id).questions().pendingFor(id);
    }

    public QuestionAnswer resolveQuestion(String id, String requestId,
                                          List<String> selected, String text) {
        AgentRun run = require(id);
        QuestionAnswer result = run.questions().resolve(requestId, selected, text);
        if (result == null) {
            throw new IllegalStateException("No pending question " + requestId + " for " + id);
        }
        return result;
    }

    public TeamService team() {
        return team;
    }

    public Map<String, Object> teamSummary(String id) {
        AgentRun run = require(id);
        if (!team.isRoot(run)) {
            throw new IllegalArgumentException("Team board is only available on a root session");
        }
        team.ensureRoot(id);
        return team.summary(id, team.enabled(id));
    }

    public Map<String, Object> setTeamEnabled(String id, boolean enabled) {
        AgentRun run = require(id);
        team.setEnabled(run, enabled);
        return team.summary(id, enabled);
    }

    public TeamTask teamTask(String id, String taskId) {
        AgentRun run = require(id);
        return team.task(run, taskId);
    }

    public boolean resolveTeamPermission(String rootId, String requestId, PermissionDecision decision,
                                         PermissionScope scope, String reason) {
        AgentRun run = require(rootId);
        if (!team.isRoot(run)) {
            throw new IllegalArgumentException("only the Lead session can approve team permissions");
        }
        return team.resolvePermission(rootId, requestId, decision, scope, reason);
    }

    public PermissionResult resolvePermission(String id, String requestId,
                                              PermissionDecision decision, PermissionScope scope,
                                              String reason) {
        AgentRun run = require(id);
        PermissionResult result = run.permissions().resolve(requestId, decision, scope, reason);
        if (result == null) {
            throw new IllegalStateException("No pending permission request " + requestId + " for " + id);
        }
        return result;
    }

    public Map<String, Object> configSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("version", AppConfig.VERSION);
        summary.put("model", config.llm().model());
        summary.put("baseUrl", config.llm().baseUrl());
        summary.put("apiKeyConfigured", config.hasApiKey());
        summary.put("maxSteps", config.agent().maxSteps());
        summary.put("tokenBudget", config.agent().tokenBudget());
        summary.put("maxToolCallsPerStep", config.agent().maxToolCallsPerStep());
        summary.put("contextCharBudget", config.agent().contextCharBudget());
        summary.put("contextTokenBudget", config.agent().contextTokenBudget());
        summary.put("commandTimeoutSeconds", config.agent().commandTimeoutSeconds());
        summary.put("autoVerify", config.agent().autoVerify());
        summary.put("goalMode", config.agent().goalMode());
        summary.put("overflowRecovery", config.agent().overflowRecovery());
        summary.put("skillPreload", config.agent().preloadSkills());
        summary.put("permissionMode", config.agent().permissionMode().id());
        summary.put("workspaceRoot", config.workspacesDir().toString());
        summary.put("skillsDir", config.skillsDir().toString());
        summary.put("toolCount", tools.names().size());
        summary.put("skillCount", skills.all().size());
        summary.put("mcp", mcp.summary());
        Map<String, Object> memMap = new LinkedHashMap<>(memory.health().toMap());
        if (config.memory() != null) {
            memMap.put("admissionMode", config.memory().admissionMode().id());
        }
        memMap.put("pendingCount", listPendingMemories(100).size());
        summary.put("memory", memMap);
        summary.put("teamDefaultEnabled", team.defaultEnabled());
        summary.put("languages", Language.ids());
        summary.put("sandboxBackend", config.sandbox().backend());
        summary.put("sandboxPlugin", sandboxRegistry.summary(config.sandbox()));
        if (config.sandbox().isDocker()) {
            summary.put("sandbox", "docker");
            summary.put("sandboxNetwork", !"off".equals(config.sandbox().dockerNetwork()));
            summary.put("sandboxDescription", "docker container (" + config.sandbox().dockerImage()
                    + "; network=" + config.sandbox().dockerNetwork() + ")");
        } else {
            CommandSandbox sandbox = new CommandRunner().sandbox();
            summary.put("sandbox", sandbox.modeName());
            summary.put("sandboxNetwork", sandbox.networkEnabled());
            summary.put("sandboxDescription", sandbox.description());
        }
        return summary;
    }

    private Verifier verifierForRun(AgentRun run) {
        SandboxSession session = run.sandboxSession();
        if (session == null) {
            synchronized (run) {
                session = run.sandboxSession();
                if (session == null) {
                    String sessionId = run.state().workspace() != null && !run.state().workspace().isBlank()
                            ? run.state().workspace()
                            : run.id();
                    session = sandboxRegistry.openSession(sessionId, run.workspace().root(), config.sandbox());
                    run.setSandboxSession(session);
                }
            }
        }
        return new Verifier(new CommandRunner(session));
    }

    private MemoryScope managementMemoryScope() {
        io.github.jasoncai0.agent.memory.MemorySettings settings = config.memory() == null
                ? io.github.jasoncai0.agent.memory.MemorySettings.defaults()
                : config.memory();
        return io.github.jasoncai0.agent.memory.MemoryPolicy.scope(
                settings, config.workspacesDir(), config.workspacesDir(), "management");
    }

    public List<PendingMemoryItem> listPendingMemories(int limit) {
        return pendingMemories.listPending(managementMemoryScope().projectId(), limit);
    }

    public PendingMemoryItem approvePendingMemory(String id) {
        return memoryPipeline.approvePending(id, managementMemoryScope());
    }

    public PendingMemoryItem rejectPendingMemory(String id) {
        return memoryPipeline.rejectPending(id);
    }

    public MemoryAdmissionPipeline memoryPipeline() {
        return memoryPipeline;
    }

    public SandboxRegistry sandboxRegistry() {
        return sandboxRegistry;
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

    public McpManager mcp() {
        return mcp;
    }

    public MemoryService memory() {
        return memory;
    }

    public SkillRegistry skills() {
        return skills;
    }

    public AppConfig config() {
        return config;
    }

    private static EventStore createStore(AppConfig config) {
        if (config.mysql() == null) {
            return null;
        }
        return new MySqlEventStore(config.mysql());
    }

    private String uniqueName() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String suffix = UUID.randomUUID().toString().substring(0, 6);
            String candidate = "run-" + LocalDateTime.now().format(STAMP) + "-" + suffix;
            if (!Files.exists(config.workspacesDir().resolve(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique workspace name");
    }

    static String validateName(String raw) {
        String name = raw.strip();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}") || name.contains("..")) {
            throw new IllegalArgumentException(
                    "Workspace name must be one segment of letters, digits, dot, dash or underscore");
        }
        return name;
    }

    public EventStore.Page history(String id, long before, int limit, boolean compact) {
        return SessionForkService.history(require(id), eventStore, before, limit, compact);
    }

    public Optional<AgentEvent> fullEvent(String id, long seq) {
        return SessionForkService.fullEvent(require(id), eventStore, seq);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        SubagentService children = subagents;
        if (children != null) {
            children.close();
        }
        runs.values().forEach(run -> run.control().cancel());
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        team.close();
        mcp.close();
        memory.close();
        pendingMemories.close();
        for (AgentRun run : runs.values()) {
            run.closeSandbox();
            run.permissions().close();
            run.questions().close();
            run.events().close();
        }
        if (eventStore != null) {
            eventStore.close();
        }
        Log.info("agent.service.closed", Map.of("runs", runs.size()));
    }
}
