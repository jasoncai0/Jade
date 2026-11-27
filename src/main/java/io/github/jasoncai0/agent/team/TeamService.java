package io.github.jasoncai0.agent.team;

import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.agent.RunSnapshot;
import io.github.jasoncai0.agent.agent.RunStatus;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionScope;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-root team runtime. Tasks are durable; members are live, shared-workspace
 * agent sessions. Team members are intentionally unbounded per user decision.
 */
public final class TeamService implements AutoCloseable {
    private static final int MAX_TASKS = 256;

    private final AgentService agents;
    private final TeamTaskStore store;
    private final boolean defaultEnabled;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, TeamMember> membersById = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<TeamMember>> membersByRoot = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.BlockingQueue<String>> leadInbox = new ConcurrentHashMap<>();
    private final Map<String, Object> rootLocks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TeamService(AgentService agents, TeamTaskStore store, boolean defaultEnabled) {
        this.agents = agents;
        this.store = store;
        this.defaultEnabled = defaultEnabled;
    }

    public void ensureRoot(String rootRunId) {
        store.ensureTeam(rootRunId, defaultEnabled);
    }

    public boolean enabled(String rootRunId) {
        return store.enabled(rootRunId);
    }

    public boolean enabledFor(AgentRun run) {
        String root = rootFor(run);
        return root != null && enabled(root);
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public void setEnabled(AgentRun root, boolean enabled) {
        if (!isRoot(root)) {
            throw new IllegalArgumentException("team toggle is only available on a root session");
        }
        store.setEnabled(root.id(), enabled);
    }

    public String rootFor(AgentRun run) {
        TeamMember member = membersById.get(run.id());
        if (member != null) {
            return member.rootRunId();
        }
        if (run.state().isSubagent()) {
            return null;
        }
        return run.id();
    }

    public boolean isRoot(AgentRun run) {
        return !run.state().isSubagent();
    }

    public boolean isLead(AgentRun run) {
        String root = rootFor(run);
        return root != null && root.equals(run.id());
    }

    public boolean isMember(AgentRun run) {
        return membersById.containsKey(run.id());
    }

    public String teamPolicy(AgentRun run) {
        if (!enabledFor(run)) {
            return "";
        }
        if (isLead(run)) {
            return """
                    ## Team mode (enabled)
                    You are the Lead. The team board is durable and shared with teammates.
                    Use team_task_create/list/get/update to define work; team_spawn to create a writable teammate;
                    list_agents/send_message/wait_agent to coordinate. Only the Lead may reassign tasks or approve
                    teammate permission requests in the UI. Task changes do not wake agents; messages do.
                    wait_agent timeout only means its observation window expired: still_running/queued members are
                    not failures. If wait_agent returns waiting_approval, ask the user to approve the child request
                    in the Team dialog/API; do not reassign or spawn duplicates. Inspect list_agents and the task
                    board, then send_message or wait again. Keep final verification in this Lead session.
                    """;
        }
        return """
                ## Team mode (teammate)
                You are a teammate on the shared team board. Claim ready tasks, do the work in the shared workspace,
                update task status and write scopes, and report back with send_message target="lead" when done or blocked.
                You may spawn further teammates with team_spawn. Do not ask the end user directly; ask the Lead.
                """;
    }

    public TeamTask createTask(AgentRun actor, String subject, String description,
                               List<String> blockedBy, List<String> writeScopes) {
        String root = requireEnabledRoot(actor);
        String cleanSubject = validateSubject(subject);
        synchronized (lock(root)) {
            List<TeamTask> tasks = store.list(root);
            Map<String, TeamTask> byId = indexById(tasks);
            long active = tasks.stream().filter(t -> t.status() != TeamTaskStatus.DELETED).count();
            if (active >= MAX_TASKS) {
                throw new IllegalArgumentException("team already has " + MAX_TASKS + " active tasks");
            }
            List<String> deps = safe(blockedBy);
            for (String id : deps) {
                TeamTask blocker = byId.get(id);
                if (blocker == null || blocker.status() == TeamTaskStatus.DELETED) {
                    throw new IllegalArgumentException("unknown blockedBy task: " + id);
                }
            }
            return store.create(root, cleanSubject, description == null ? "" : description,
                    deps, normalizeScopes(writeScopes));
        }
    }

    public List<TeamTask> tasks(AgentRun actor) {
        String root = requireEnabledRoot(actor);
        return store.list(root);
    }

    public TeamTask task(AgentRun actor, String taskId) {
        String root = requireEnabledRoot(actor);
        return store.find(root, taskId).orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
    }

    public TeamTask updateTask(AgentRun actor, String taskId, String action, long expectedRevision,
                               String subject, String description, List<String> blockedBy,
                               List<String> writeScopes, String owner) {
        String root = requireEnabledRoot(actor);
        synchronized (lock(root)) {
            List<TeamTask> all = store.list(root);
            Map<String, TeamTask> byId = indexById(all);
            TeamTask task = byId.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("task not found: " + taskId);
            }
            if (task.revision() != expectedRevision) {
                throw new IllegalStateException("team task revision changed; refresh the board");
            }
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("action is required");
            }
            String ownerId = actor.id();
            String ownerName = isLead(actor) ? "lead" : member(actor).name();
            boolean privileged = isLead(actor) || ownerId.equals(task.ownerId());
            TeamTask next = switch (action.trim().toLowerCase()) {
                case "claim" -> {
                    if (!task.status().active()) throw new IllegalArgumentException("only active tasks can be claimed");
                    if (task.ownerId() != null) throw new IllegalArgumentException("task already has an owner");
                    if (!ready(byId, task)) throw new IllegalArgumentException("task is blocked by unfinished dependencies");
                    yield task.ownedBy(ownerId, ownerName, TeamTaskStatus.IN_PROGRESS, System.currentTimeMillis());
                }
                case "release" -> {
                    if (!privileged) throw new IllegalArgumentException("only the owner or Lead can release");
                    yield task.ownedBy(null, null, TeamTaskStatus.PENDING, System.currentTimeMillis());
                }
                case "complete" -> {
                    if (!privileged) throw new IllegalArgumentException("only the owner or Lead can complete");
                    yield task.withStatus(TeamTaskStatus.COMPLETED, System.currentTimeMillis());
                }
                case "reopen" -> {
                    if (!privileged) throw new IllegalArgumentException("only the owner or Lead can reopen");
                    yield task.ownedBy(null, null, TeamTaskStatus.PENDING, System.currentTimeMillis());
                }
                case "reassign" -> {
                    if (!isLead(actor)) throw new IllegalArgumentException("only the Lead can reassign");
                    if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
                    TeamMember target = memberByName(root, owner);
                    yield task.ownedBy(target.id(), target.name(), TeamTaskStatus.PENDING, System.currentTimeMillis());
                }
                case "edit" -> {
                    if (!privileged) throw new IllegalArgumentException("only the owner or Lead can edit");
                    String nextSubject = subject == null || subject.isBlank() ? null : validateSubject(subject);
                    List<String> nextBlockedBy = blockedBy == null
                            ? null
                            : validateDependencies(byId, task.id(), blockedBy);
                    yield task.withFields(nextSubject, description, nextBlockedBy,
                            writeScopes == null ? null : normalizeScopes(writeScopes), System.currentTimeMillis());
                }
                case "set_dependencies" -> {
                    if (!privileged) throw new IllegalArgumentException("only the owner or Lead can change dependencies");
                    List<String> deps = validateDependencies(byId, task.id(), blockedBy);
                    yield task.withFields(null, null, deps, null, System.currentTimeMillis())
                            .withStatus(TeamTaskStatus.PENDING, System.currentTimeMillis());
                }
                case "delete" -> {
                    if (!privileged) throw new IllegalArgumentException("only the owner or Lead can delete");
                    for (TeamTask other : all) {
                        if (other.status() != TeamTaskStatus.DELETED && other.blockedBy().contains(task.id())) {
                            throw new IllegalArgumentException("task is still a dependency of " + other.id());
                        }
                    }
                    yield task.ownedBy(null, null, TeamTaskStatus.DELETED, System.currentTimeMillis());
                }
                default -> throw new IllegalArgumentException("unknown task action: " + action);
            };
            TeamTask saved = store.save(root, task, next);
            if (saved == null) throw new IllegalStateException("team task revision changed; refresh the board");
            return saved;
        }
    }

    public TeamMember spawn(AgentRun actor, String requestedName, String task) {
        String root = requireEnabledRoot(actor);
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task is required");
        String name = uniqueName(root, requestedName);
        AgentRun child = agents.newTeamRun(actor, root, name, task);
        TeamMember member = new TeamMember(child.id(), name, root, actor.id(), child);
        membersById.put(child.id(), member);
        membersByRoot.computeIfAbsent(root, ignored -> new CopyOnWriteArrayList<>()).add(member);
        member.inbox().offer(new TeamMember.InboxMessage(task, false));
        try {
            executor.submit(() -> memberLoop(member));
        } catch (RuntimeException e) {
            membersById.remove(child.id());
            membersByRoot.getOrDefault(root, new CopyOnWriteArrayList<>()).remove(member);
            throw e;
        }
        return member;
    }

    public String send(AgentRun actor, String target, String message) {
        String root = requireEnabledRoot(actor);
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
        String name = target == null ? "" : target.trim();
        if (name.equals("lead") || name.equals(root)) {
            if (isLead(actor)) throw new IllegalArgumentException("Lead cannot message itself");
            leadInbox.computeIfAbsent(root, ignored -> new java.util.concurrent.LinkedBlockingQueue<>()).offer(message.trim());
            return "queued to lead";
        }
        TeamMember member = memberByName(root, name);
        member.inbox().offer(new TeamMember.InboxMessage(message.trim(), true));
        return "queued to " + member.name();
    }

    public Map<String, Object> waitFor(AgentRun actor, List<String> targets, long timeoutMillis) {
        String root = requireEnabledRoot(actor);
        List<TeamMember> members = resolveTargets(root, targets);
        long started = System.currentTimeMillis();
        long deadline = started + Math.max(1, timeoutMillis);
        boolean idle = members.isEmpty();
        boolean waitingApproval = false;
        while (!idle && System.currentTimeMillis() < deadline) {
            idle = true;
            waitingApproval = false;
            for (TeamMember member : members) {
                if (member.run().permissions().pendingFor(member.id()) != null) {
                    waitingApproval = true;
                    idle = false;
                    break;
                }
                RunStatus status = RunStatus.valueOf(member.run().state().snapshot().status());
                if (member.running() || !member.inbox().isEmpty() || status == RunStatus.QUEUED) {
                    idle = false;
                    break;
                }
            }
            if (idle || waitingApproval) break;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean timedOut = !idle && !waitingApproval;
        List<String> stillRunning = new ArrayList<>();
        List<String> queued = new ArrayList<>();
        List<String> waitingApprovals = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> idleMembers = new ArrayList<>();
        for (TeamMember member : members) {
            switch (outcome(member)) {
                case "running" -> stillRunning.add(member.name());
                case "queued" -> queued.add(member.name());
                case "waiting_approval" -> waitingApprovals.add(member.name());
                case "completed" -> completed.add(member.name());
                case "failed" -> failed.add(member.name());
                default -> idleMembers.add(member.name());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timedOut", timedOut);
        result.put("waitedMillis", System.currentTimeMillis() - started);
        result.put("waitingApproval", waitingApprovals);
        result.put("pendingPermissions", pendingPermissions(root));
        result.put("stillRunning", stillRunning);
        result.put("queued", queued);
        result.put("completed", completed);
        result.put("failed", failed);
        result.put("idle", idleMembers);
        result.put("members", members.stream().map(this::memberView).toList());
        if (isLead(actor)) {
            List<String> messages = new ArrayList<>();
            java.util.concurrent.BlockingQueue<String> queue = leadInbox.get(root);
            if (queue != null) queue.drainTo(messages);
            result.put("messages", messages);
        }
        return result;
    }

    public List<Map<String, Object>> listMembers(AgentRun actor) {
        String root = requireEnabledRoot(actor);
        return membersByRoot.getOrDefault(root, new CopyOnWriteArrayList<>()).stream()
                .sorted(Comparator.comparing(TeamMember::name)).map(this::memberView).toList();
    }

    public List<Map<String, Object>> pendingPermissions(String rootRunId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TeamMember member : membersByRoot.getOrDefault(rootRunId, new CopyOnWriteArrayList<>())) {
            PermissionRequest request = member.run().permissions().pendingFor(member.id());
            if (request != null) {
                Map<String, Object> row = new LinkedHashMap<>(request.publicView());
                row.put("memberId", member.id());
                row.put("memberName", member.name());
                result.add(row);
            }
        }
        return result;
    }

    public boolean resolvePermission(String rootRunId, String requestId, PermissionDecision decision,
                                     PermissionScope scope, String reason) {
        for (TeamMember member : membersByRoot.getOrDefault(rootRunId, new CopyOnWriteArrayList<>())) {
            PermissionRequest request = member.run().permissions().pendingFor(member.id());
            if (request != null && request.id().equals(requestId)) {
                return member.run().permissions().resolve(requestId, decision, scope, reason) != null;
            }
        }
        return false;
    }

    public Map<String, Object> summary(String rootRunId, boolean enabled) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("defaultEnabled", defaultEnabled);
        map.put("tasks", store.list(rootRunId));
        map.put("members", membersByRoot.getOrDefault(rootRunId, new CopyOnWriteArrayList<>())
                .stream().map(this::memberView).toList());
        map.put("pendingPermissions", pendingPermissions(rootRunId));
        return map;
    }

    public TeamMember memberByRun(String runId) {
        return membersById.get(runId);
    }

    private void memberLoop(TeamMember member) {
        while (!closed && !member.closed()) {
            TeamMember.InboxMessage message;
            try {
                message = member.inbox().take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (closed || member.closed() || message.text().isBlank() && !message.continuation()) {
                break;
            }
            member.running(true);
            member.currentTask(message.text());
            member.lastError(null);
            try {
                RunSnapshot snapshot = agents.runTeamTurn(member, message.text(), message.continuation());
                member.lastResult(snapshot.finalAnswer() == null ? snapshot.status() : snapshot.finalAnswer());
            } catch (Throwable t) {
                member.lastError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            } finally {
                member.running(false);
                synchronized (member.monitor()) {
                    member.monitor().notifyAll();
                }
            }
        }
    }

    private List<TeamMember> resolveTargets(String root, List<String> targets) {
        List<TeamMember> all = membersByRoot.getOrDefault(root, new CopyOnWriteArrayList<>());
        if (targets == null || targets.isEmpty()) {
            return new ArrayList<>(all);
        }
        List<TeamMember> selected = new ArrayList<>();
        for (String target : targets) {
            selected.add(memberByName(root, target));
        }
        return selected;
    }

    private TeamMember memberByName(String root, String value) {
        for (TeamMember member : membersByRoot.getOrDefault(root, new CopyOnWriteArrayList<>())) {
            if (member.name().equals(value) || member.id().equals(value)) {
                return member;
            }
        }
        throw new IllegalArgumentException("unknown team member: " + value);
    }

    private TeamMember member(AgentRun actor) {
        TeamMember member = membersById.get(actor.id());
        if (member == null) throw new IllegalArgumentException("not a team member: " + actor.id());
        return member;
    }

    private String uniqueName(String root, String requested) {
        String seed = requested == null || requested.isBlank() ? "agent" : requested.trim().toLowerCase()
                .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("(^-|-$)", "");
        if (seed.isBlank()) seed = "agent";
        if (seed.length() > 32) seed = seed.substring(0, 32);
        String base = seed;
        int n = 1;
        while (nameExists(root, seed) || seed.equals("lead")) {
            seed = base + "-" + n++;
        }
        return seed;
    }

    private boolean nameExists(String root, String name) {
        return membersByRoot.getOrDefault(root, new CopyOnWriteArrayList<>()).stream()
                .anyMatch(m -> m.name().equals(name));
    }

    public void cancelRoot(String rootRunId) {
        if (rootRunId == null || rootRunId.isBlank()) {
            return;
        }
        for (TeamMember member : membersByRoot.getOrDefault(rootRunId, new CopyOnWriteArrayList<>())) {
            member.inbox().clear();
            member.run().permissions().cancelAll("root session cancelled");
            member.run().questions().cancelAll("root session cancelled");
            member.run().control().cancel();
            member.close();
        }
    }

    private static Map<String, TeamTask> indexById(List<TeamTask> tasks) {
        Map<String, TeamTask> byId = new LinkedHashMap<>();
        for (TeamTask task : tasks) {
            byId.put(task.id(), task);
        }
        return byId;
    }

    private static String validateSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        String clean = subject.trim();
        if (clean.length() > 200) {
            throw new IllegalArgumentException("subject must be at most 200 characters");
        }
        return clean;
    }

    private static List<String> validateDependencies(Map<String, TeamTask> byId, String taskId,
                                                     List<String> rawDeps) {
        List<String> deps = safe(rawDeps);
        for (String dep : deps) {
            if (dep.equals(taskId)) {
                throw new IllegalArgumentException("a task cannot block itself");
            }
            TeamTask target = byId.get(dep);
            if (target == null || target.status() == TeamTaskStatus.DELETED) {
                throw new IllegalArgumentException("unknown blocker: " + dep);
            }
            if (reaches(byId, dep, taskId, new java.util.HashSet<>())) {
                throw new IllegalArgumentException("dependency cycle detected between " + taskId + " and " + dep);
            }
        }
        return deps;
    }

    private static boolean reaches(Map<String, TeamTask> byId, String currentId,
                                   String goalId, java.util.Set<String> visited) {
        if (currentId.equals(goalId)) {
            return true;
        }
        if (!visited.add(currentId)) {
            return false;
        }
        TeamTask node = byId.get(currentId);
        if (node == null || node.status() == TeamTaskStatus.DELETED) {
            return false;
        }
        for (String next : node.blockedBy()) {
            if (reaches(byId, next, goalId, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean ready(Map<String, TeamTask> byId, TeamTask task) {
        for (String id : task.blockedBy()) {
            TeamTask blocker = byId.get(id);
            if (blocker == null || blocker.status() != TeamTaskStatus.COMPLETED) return false;
        }
        return true;
    }

    private String requireEnabledRoot(AgentRun actor) {
        String root = rootFor(actor);
        if (root == null) throw new IllegalArgumentException("not a team-capable session");
        if (!enabled(root)) throw new IllegalStateException("team mode is disabled for this session");
        return root;
    }

    private Object lock(String root) {
        return rootLocks.computeIfAbsent(root, ignored -> new Object());
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }

    private static List<String> normalizeScopes(List<String> values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String path = value.replace('\\', '/').trim().replaceAll("^/+", "");
            if (path.contains("..")) throw new IllegalArgumentException("write scope cannot contain ..");
            if (!result.contains(path)) result.add(path);
        }
        return List.copyOf(result);
    }

    private String outcome(TeamMember member) {
        if (member.run().permissions().pendingFor(member.id()) != null) {
            return "waiting_approval";
        }
        if (member.running()) {
            return "running";
        }
        if (!member.inbox().isEmpty()) {
            return "queued";
        }
        if (member.lastError() != null) {
            return "failed";
        }
        RunStatus status = RunStatus.valueOf(member.run().state().snapshot().status());
        if (status == RunStatus.SUCCEEDED) {
            return "completed";
        }
        if (status == RunStatus.FAILED || status == RunStatus.CANCELLED
                || status == RunStatus.MAX_STEPS || status == RunStatus.LOOP_DETECTED
                || status == RunStatus.BUDGET_EXCEEDED) {
            return "failed";
        }
        return "idle";
    }

    private Map<String, Object> memberView(TeamMember member) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", member.id());
        map.put("name", member.name());
        map.put("rootRunId", member.rootRunId());
        map.put("parentRunId", member.parentRunId());
        map.put("running", member.running());
        map.put("currentTask", member.currentTask());
        map.put("lastResult", member.lastResult());
        map.put("lastError", member.lastError());
        String outcome = outcome(member);
        map.put("state", outcome);
        map.put("lastOutcome", outcome);
        map.put("status", member.run().state().snapshot().status());
        PermissionRequest pending = member.run().permissions().pendingFor(member.id());
        map.put("pendingPermission", pending == null ? null : pending.publicView());
        return map;
    }

    @Override
    public void close() {
        closed = true;
        for (TeamMember member : membersById.values()) {
            member.close();
        }
        executor.shutdownNow();
        try {
            store.close();
        } catch (Exception ignored) {
        }
    }
}
