package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.history.EventStore;
import io.github.jasoncai0.agent.history.MySqlEventStore;
import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.DeepSeekClient;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.mcp.McpManager;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.NoopMemoryService;
import io.github.jasoncai0.agent.memory.MemoryServices;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.team.TeamMember;
import io.github.jasoncai0.agent.team.TeamService;
import io.github.jasoncai0.agent.team.TeamStores;
import io.github.jasoncai0.agent.team.TeamToolset;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.tool.builtin.DelegateTaskTool;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.permission.PermissionScope;
import io.github.jasoncai0.agent.question.QuestionAnswer;
import io.github.jasoncai0.agent.question.QuestionBroker;
import io.github.jasoncai0.agent.question.QuestionRequest;
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
    private static final ObjectMapper REBASE_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_TASK_CHARS = 20_000;
    private static final int MAX_MESSAGE_CHARS = 20_000;
    private static final int MAX_RESTORED_SESSIONS = 200;

    private final AppConfig config;
    private final LlmClient llm;
    private final SkillRegistry skills;
    private final ToolRegistry tools;
    private final McpManager mcp;
    private final MemoryService memory;
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
        this.memory = MemoryServices.create(config.memory());
        this.team = new TeamService(this, TeamStores.create(config.mysql()), config.teamDefault());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Files.createDirectories(config.workspacesDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create workspaces directory " + config.workspacesDir(), e);
        }
        if (eventStore != null && eventStore.isEmpty()) {
            migrateLegacySessions();
        }
        restoreRuns();
        Log.info("agent.service.ready", Map.of(
                "model", config.llm().model(),
                "skills", skills.all().size(),
                "tools", tools.names().size(),
                "memory", memory.health().available(),
                "apiKeyConfigured", config.hasApiKey()
        ));
    }

    /**
     * Starts a run in a fresh, isolated directory. The optional name is a single
     * path segment under agent-workspaces; omit it to generate a unique one.
     */
    public RunSnapshot start(String task, String workspaceName) {
        return start(task, workspaceName, false);
    }

    /**
     * Starts a run and optionally enables Team mode before the first model turn.
     */
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

        String id = "run-" + java.util.UUID.randomUUID().toString().substring(0, 8);
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

    /**
     * Continues a terminal session with another user message. The workspace,
     * transcript, and event sequence survive; only the per-turn loop resets.
     */
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

    /** New run id plus the parent it was copied from. */
    public record ForkResult(String runId, String parentRunId, String workspace) {
    }

    /**
     * Copies a finished session into a new, independent session: workspace,
     * transcript prefix and event history. The fork starts terminal and idle;
     * the first follow-up message opens a new turn in the copied workspace.
     */
    public ForkResult fork(String parentId, String name) {
        if (closed) {
            throw new IllegalStateException("Agent service is shutting down");
        }
        AgentRun parent = require(parentId);
        RunSnapshot parentSnapshot = parent.snapshot();
        if (parent.running() || !parentSnapshot.terminal()) {
            throw new IllegalStateException("Only a finished session can be forked");
        }
        Path source = parent.workspace().root();
        String parentWorkspace = parentSnapshot.workspace();
        String newId = "fork-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Workspace target;
        String directoryName;
        synchronized (startLock) {
            String base = name == null || name.isBlank()
                    ? validateName(parentWorkspace)
                    : validateName(name);
            directoryName = base + "-fork-" + java.util.UUID.randomUUID().toString().substring(0, 6);
            Path directory = config.workspacesDir().resolve(directoryName).normalize();
            if (!directory.startsWith(config.workspacesDir())) {
                throw new IllegalArgumentException("Invalid workspace name");
            }
            if (Files.exists(directory)) {
                throw new IllegalArgumentException("Workspace already exists: " + directoryName);
            }
            target = Workspace.open(directory);
            WorkspaceFork.copy(source, target.root());
        }

        List<ChatMessage> messages = RunArtifacts.loadMessages(source);
        List<AgentEvent> parentEvents = loadForkEvents(parent);
        long forkSeq = parentEvents.stream().mapToLong(AgentEvent::seq).max().orElse(0L);
        long now = System.currentTimeMillis();
        List<String> files = target.listFiles(500, 8).stream()
                .map(Workspace.FileEntry::path)
                .toList();
        RunSnapshot forkSnapshot = new RunSnapshot(
                newId,
                parentSnapshot.task(),
                directoryName,
                parentSnapshot.status(),
                parentSnapshot.phase(),
                parentSnapshot.phaseLabel(),
                parentSnapshot.step(),
                parentSnapshot.maxSteps(),
                parentSnapshot.plan(),
                files,
                parentSnapshot.usage(),
                parentSnapshot.finalAnswer(),
                parentSnapshot.error(),
                parentSnapshot.verification(),
                now,
                now,
                ".agent/transcript.json",
                parentId,
                forkSeq
        );
        RunState state = RunState.restore(forkSnapshot);
        RunEventLog events = new RunEventLog(newId, eventStore);
        for (AgentEvent event : parentEvents) {
            events.append(new AgentEvent(event.seq(), event.timestamp(), event.type(),
                    rebase(event.data(), parentId, newId, parentWorkspace, directoryName)));
        }
        events.append(new AgentEvent(forkSeq + 1, now, "fork.created", Map.of(
                "parentRunId", parentId,
                "parentSeq", forkSeq,
                "workspace", directoryName,
                "task", parentSnapshot.task()
        )));
        events.close();

        AgentRun run = new AgentRun(newId, target, state, events);
        runs.put(newId, run);
        RunArtifacts.persist(target, state.snapshot(), messages, events);
        if (eventStore != null) {
            eventStore.saveSnapshot(state.snapshot());
        }
        Log.info("agent.run.forked", Map.of(
                "runId", newId,
                "parentRunId", parentId,
                "workspace", directoryName,
                "events", parentEvents.size()
        ));
        return new ForkResult(newId, parentId, directoryName);
    }

    private List<AgentEvent> loadForkEvents(AgentRun parent) {
        List<AgentEvent> events = new ArrayList<>();
        if (eventStore != null) {
            long before = 0;
            while (events.size() < 20_000) {
                EventStore.Page page = eventStore.page(parent.id(), before, 500, true);
                events.addAll(page.events());
                if (!page.hasMore()) {
                    break;
                }
                before = page.nextBefore();
            }
        } else {
            events.addAll(RunArtifacts.load(parent.workspace().root())
                    .map(RunArtifacts.StoredRun::events)
                    .orElse(List.of()));
        }
        events.sort(Comparator.comparingLong(AgentEvent::seq));
        if (events.size() > 20_000) {
            events = new ArrayList<>(events.subList(events.size() - 20_000, events.size()));
        }
        return events;
    }

    /**
     * Rewrites the parent's id and workspace inside a copied event payload so a
     * fork never claims the parent's identity in the UI or in snapshot fields.
     */
    private static Map<String, Object> rebase(Map<String, Object> data, String parentId,
                                              String newId, String parentWorkspace,
                                              String newWorkspace) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rebaseValue(
                data, parentId, newId, parentWorkspace, newWorkspace);
        return result == null ? Map.of() : result;
    }

    private static Object rebaseValue(Object value, String parentId, String newId,
                                      String parentWorkspace, String newWorkspace) {
        if (value != null && value.getClass().isRecord()) {
            Object converted = REBASE_MAPPER.convertValue(value, Object.class);
            return rebaseValue(converted, parentId, newId, parentWorkspace, newWorkspace);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nested = entry.getValue();
                if (("id".equals(key) || "runId".equals(key)) && parentId.equals(nested)) {
                    out.put(key, newId);
                } else if ("workspace".equals(key) && parentWorkspace.equals(nested)) {
                    out.put(key, newWorkspace);
                } else {
                    out.put(key, rebaseValue(nested, parentId, newId, parentWorkspace, newWorkspace));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(rebaseValue(item, parentId, newId, parentWorkspace, newWorkspace));
            }
            return out;
        }
        return value;
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

    /**
     * Parent runs get a per-run registry with {@code delegate_task}; child runs
     * never do, and their registry is also a separate read-only whitelist.
     */
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
        String id = "sub-" + java.util.UUID.randomUUID().toString().substring(0, 8);
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
                    new Verifier(), new PermissionBroker(), new PermissionSession(),
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
        String id = "team-" + java.util.UUID.randomUUID().toString().substring(0, 8);
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
                    new Verifier(), run.permissions(), run.permissionSession(), run.questions(),
                    GoalReviewer.NOOP, NoopMemoryService.off())
                    .withLiveDeltas(run.deltas())
                    .withTeamPolicy(team.teamPolicy(run));
            RunSnapshot snapshot = continuation
                    ? loop.continueSession(userMessage, RunArtifacts.loadMessages(run.workspace().root()),
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
                    new Verifier(), run.permissions(), run.permissionSession(), run.questions(),
                    new LlmGoalReviewer(llm), memory)
                    .withLiveDeltas(run.deltas());
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
        } catch (ExecutionException e) {
            return run.snapshot();
        } catch (TimeoutException e) {
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

    public io.github.jasoncai0.agent.team.TeamTask teamTask(String id, String taskId) {
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
        summary.put("memory", memory.health().toMap());
        summary.put("teamDefaultEnabled", team.defaultEnabled());
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

    /**
     * Rehydrates recent terminal runs from {@code agent-workspaces/<name>/.agent/}.
     * Sessions are read-only history: their executor died with the previous
     * process, so they are not inserted into the active-workspace set.
     */
    private void restoreRuns() {
        if (eventStore != null) {
            restoreFromStore();
            return;
        }
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
                AgentRun run = new AgentRun(snapshot.id(), workspace, state, events);
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

    private void restoreFromStore() {
        int restored = 0;
        for (RunSnapshot stored : eventStore.snapshots(MAX_RESTORED_SESSIONS)) {
            try {
                if (runs.containsKey(stored.id())) continue;
                Path directory = config.workspacesDir().resolve(stored.workspace()).normalize();
                if (!directory.startsWith(config.workspacesDir()) || !Files.isDirectory(directory)) {
                    continue;
                }
                Workspace workspace = Workspace.open(directory);
                RunSnapshot persisted = stored;
                // A previous process may have died after the terminal event but
                // before jade_sessions was updated. Prefer the durable terminal
                // event/transcript from the workspace over the stale summary.
                if (!stored.terminal()) {
                    RunSnapshot recovered = RunArtifacts.load(workspace.root())
                            .map(RunArtifacts.StoredRun::snapshot)
                            .filter(RunSnapshot::terminal)
                            .orElse(null);
                    if (recovered != null) {
                        stored = recovered;
                    }
                }
                RunState state = RunState.restore(stored);
                RunSnapshot normalized = state.snapshot();
                if (!normalized.status().equals(persisted.status())
                        || !java.util.Objects.equals(normalized.error(), persisted.error())) {
                    eventStore.saveSnapshot(normalized);
                }
                RunEventLog events = RunEventLog.restored(stored.id(), eventStore,
                        eventStore.lastSeq(stored.id()));
                runs.put(stored.id(), new AgentRun(stored.id(), workspace, state, events));
                restored++;
            } catch (RuntimeException e) {
                Log.warn("agent.sessions.mysql_restore_failed", Map.of(
                        "runId", stored.id(),
                        "error", String.valueOf(e.getMessage())));
            }
        }
        Log.info("agent.sessions.restored", Map.of("restored", restored, "store", "mysql"));
    }

    /** One-time import of pre-MySQL transcript/event files. */
    private void migrateLegacySessions() {
        int imported = 0;
        try (Stream<Path> entries = Files.list(config.workspacesDir())) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(directory.resolve(".agent/transcript.json"))) continue;
                try {
                    Workspace workspace = Workspace.open(directory);
                    RunArtifacts.StoredRun stored = RunArtifacts.load(workspace.root()).orElse(null);
                    if (stored == null) continue;
                    eventStore.importRun(stored.snapshot(), stored.events());
                    imported++;
                } catch (RuntimeException e) {
                    Log.warn("agent.migration.skip", Map.of(
                            "workspace", directory.getFileName().toString(),
                            "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException e) {
            Log.warn("agent.migration.scan_failed", Map.of("error", String.valueOf(e.getMessage())));
        }
        Log.info("agent.sessions.migrated", Map.of("imported", imported));
    }

    private long sessionModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory.resolve(".agent/transcript.json")).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static EventStore createStore(AppConfig config) {
        if (config.mysql() == null) return null;
        return new MySqlEventStore(config.mysql());
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

    public EventStore.Page history(String id, long before, int limit, boolean compact) {
        require(id);
        if (eventStore == null) {
            List<AgentEvent> events = require(id).events().snapshot();
            long max = events.stream().mapToLong(AgentEvent::seq).max().orElse(0);
            List<AgentEvent> tail = events.stream()
                    .filter(e -> before <= 0 || e.seq() < before)
                    .skip(Math.max(0, events.size() - limit))
                    .toList();
            long next = tail.isEmpty() ? before : tail.get(0).seq();
            return new EventStore.Page(tail, next, false, max, events.size());
        }
        return eventStore.page(id, before, limit, compact);
    }

    public Optional<AgentEvent> fullEvent(String id, long seq) {
        require(id);
        if (eventStore == null) {
            return require(id).events().snapshot().stream().filter(e -> e.seq() == seq).findFirst();
        }
        return eventStore.event(id, seq);
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
        for (AgentRun run : runs.values()) {
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
