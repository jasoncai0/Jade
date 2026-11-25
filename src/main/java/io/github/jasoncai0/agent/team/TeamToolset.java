package io.github.jasoncai0.agent.team;

import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All team tools for one concrete run. They are registered only when team mode is enabled. */
public final class TeamToolset {
    private static final List<String> ACTIONS = List.of(
            "claim", "release", "complete", "reopen", "reassign", "edit", "set_dependencies", "delete");

    private TeamToolset() {
    }

    public static void register(ToolRegistry registry, TeamService team, AgentRun actor) {
        registry.register(new Create(team, actor));
        registry.register(new ListTasks(team, actor));
        registry.register(new Get(team, actor));
        registry.register(new Update(team, actor));
        registry.register(new Spawn(team, actor));
        registry.register(new ListAgents(team, actor));
        registry.register(new Send(team, actor));
        registry.register(new Wait(team, actor));
    }

    private static List<String> strings(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Argument '" + key + "' must be an array");
        return list.stream().filter(v -> v != null && !String.valueOf(v).isBlank()).map(String::valueOf).toList();
    }

    private static String view(TeamTask task) {
        StringBuilder out = new StringBuilder();
        out.append(task.id()).append(" [").append(task.status().id()).append("] rev=").append(task.revision())
                .append(" — ").append(task.subject());
        if (task.ownerName() != null) out.append(" @").append(task.ownerName());
        if (!task.blockedBy().isEmpty()) out.append(" blockedBy=").append(task.blockedBy());
        if (!task.writeScopes().isEmpty()) out.append(" scopes=").append(task.writeScopes());
        if (!task.description().isBlank()) out.append("\n  ").append(task.description());
        return out.toString();
    }

    private static Map<String, Object> data(TeamTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("task", task);
        return map;
    }

    private abstract static class Base implements Tool {
        protected final TeamService team;
        protected final AgentRun actor;

        Base(TeamService team, AgentRun actor) {
            this.team = team;
            this.actor = actor;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            try {
                return run(args);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return ToolResult.error(e.getMessage());
            }
        }

        abstract ToolResult run(Map<String, Object> args);
    }

    private static final class Create extends Base {
        Create(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("team_task_create",
                    "Create a durable team task. Use blockedBy task ids for dependencies; writeScopes are advisory workspace-relative paths.",
                    Schemas.object(Schemas.properties(
                            "subject", Schemas.string("Short task subject."),
                            "description", Schemas.string("Detailed task instructions and acceptance criteria."),
                            "blockedBy", Schemas.array("Task ids that must complete first.", Schemas.string("Task id.")),
                            "writeScopes", Schemas.array("Advisory workspace paths this task will write.", Schemas.string("Relative path."))
                    ), "subject"));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            TeamTask task = team.createTask(actor, ToolArgs.requiredString(args, "subject"),
                    ToolArgs.string(args, "description", ""), strings(args, "blockedBy"), strings(args, "writeScopes"));
            return ToolResult.ok("Created " + view(task), data(task));
        }
    }

    private static final class ListTasks extends Base {
        ListTasks(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("team_task_list", "List the shared team board with optional filters.",
                    Schemas.object(Schemas.properties(
                            "status", Schemas.stringEnum("Filter by status.", List.of("pending", "in_progress", "completed", "deleted")),
                            "owner", Schemas.string("Filter by owner name or run id."),
                            "ready", Schemas.bool("Only show unblocked pending tasks."),
                            "limit", Schemas.integer("Maximum rows.", 1, 100)
                    )));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            String status = ToolArgs.string(args, "status", "");
            String owner = ToolArgs.string(args, "owner", "");
            boolean readyOnly = ToolArgs.bool(args, "ready", false);
            int limit = ToolArgs.integer(args, "limit", 100, 1, 100);
            List<TeamTask> all = team.tasks(actor);
            Map<String, TeamTask> byId = new LinkedHashMap<>();
            all.forEach(t -> byId.put(t.id(), t));
            List<TeamTask> filtered = new ArrayList<>();
            for (TeamTask task : all) {
                if (!status.isBlank() && !task.status().id().equals(status)) continue;
                if (!owner.isBlank() && !owner.equals(task.ownerId()) && !owner.equals(task.ownerName())) continue;
                if (readyOnly && (task.status() != TeamTaskStatus.PENDING || task.ownerId() != null)) continue;
                if (readyOnly && task.blockedBy().stream().anyMatch(id -> {
                    TeamTask dep = byId.get(id);
                    return dep == null || dep.status() != TeamTaskStatus.COMPLETED;
                })) continue;
                filtered.add(task);
                if (filtered.size() >= limit) break;
            }
            if (filtered.isEmpty()) return ToolResult.ok("(no matching tasks)", Map.of("count", 0));
            StringBuilder out = new StringBuilder();
            for (TeamTask task : filtered) out.append(view(task)).append('\n');
            return ToolResult.ok(out.toString().stripTrailing(), Map.of("count", filtered.size()));
        }
    }

    private static final class Get extends Base {
        Get(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("team_task_get", "Get one task's full current state.",
                    Schemas.object(Schemas.properties("taskId", Schemas.string("Task id.")), "taskId"));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            TeamTask task = team.task(actor, ToolArgs.requiredString(args, "taskId"));
            return ToolResult.ok(view(task), data(task));
        }
    }

    private static final class Update extends Base {
        Update(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("team_task_update",
                    "CAS-update a task. Claim/release/complete/reopen/edit/set_dependencies/delete; only Lead can reassign.",
                    Schemas.object(Schemas.properties(
                            "taskId", Schemas.string("Task id."),
                            "action", Schemas.stringEnum("Transition.", ACTIONS),
                            "expectedRevision", Schemas.integer("Revision read from get/list.", 1, 1_000_000),
                            "subject", Schemas.string("Optional new subject."),
                            "description", Schemas.string("Optional new description."),
                            "blockedBy", Schemas.array("Optional new dependency ids.", Schemas.string("Task id.")),
                            "writeScopes", Schemas.array("Optional new paths.", Schemas.string("Relative path.")),
                            "owner", Schemas.string("Owner name for reassign.")
                    ), "taskId", "action", "expectedRevision"));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            long revision = ToolArgs.integer(args, "expectedRevision", 0, 1, 1_000_000);
            TeamTask task = team.updateTask(actor, ToolArgs.requiredString(args, "taskId"),
                    ToolArgs.requiredString(args, "action"), revision,
                    ToolArgs.string(args, "subject", null), ToolArgs.string(args, "description", null),
                    args.containsKey("blockedBy") ? strings(args, "blockedBy") : null,
                    args.containsKey("writeScopes") ? strings(args, "writeScopes") : null,
                    ToolArgs.string(args, "owner", null));
            return ToolResult.ok("Updated " + view(task), data(task));
        }
    }

    private static final class Spawn extends Base {
        Spawn(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("team_spawn",
                    "Create a writable async teammate in the same workspace. The task is queued; use wait_agent/send_message.",
                    Schemas.object(Schemas.properties(
                            "name", Schemas.string("Optional unique name."),
                            "task", Schemas.string("Initial task and acceptance criteria.")
                    ), "task"));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            TeamMember member = team.spawn(actor, ToolArgs.string(args, "name", null),
                    ToolArgs.requiredString(args, "task"));
            return ToolResult.ok("Spawned " + member.name() + " (" + member.id() + ")",
                    Map.of("id", member.id(), "name", member.name()));
        }
    }

    private static final class ListAgents extends Base {
        ListAgents(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("list_agents", "List live team members and their running/idle state.",
                    Schemas.object(Schemas.properties()));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            List<Map<String, Object>> members = team.listMembers(actor);
            if (members.isEmpty()) return ToolResult.ok("(no teammates)", Map.of("count", 0));
            StringBuilder out = new StringBuilder();
            for (Map<String, Object> member : members) {
                out.append(member.get("name")).append(" (").append(member.get("id")).append(") ")
                        .append(Boolean.TRUE.equals(member.get("running")) ? "running" : "idle");
                Object error = member.get("lastError");
                if (error != null) out.append(" error=").append(error);
                Object result = member.get("lastResult");
                if (result != null && !String.valueOf(result).isBlank()) {
                    out.append(" — ").append(String.valueOf(result).replace('\n', ' '));
                }
                out.append('\n');
            }
            return ToolResult.ok(out.toString().stripTrailing(), Map.of("count", members.size()));
        }
    }

    private static final class Send extends Base {
        Send(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("send_message", "Queue a message for the Lead or another teammate.",
                    Schemas.object(Schemas.properties(
                            "target", Schemas.string("Member name/id, or lead."),
                            "message", Schemas.string("Message text.")
                    ), "target", "message"));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            String target = ToolArgs.requiredString(args, "target");
            return ToolResult.ok(team.send(actor, target, ToolArgs.requiredString(args, "message")),
                    Map.of("target", target));
        }
    }

    private static final class Wait extends Base {
        Wait(TeamService team, AgentRun actor) {
            super(team, actor);
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("wait_agent",
                    "Wait until selected teammates are idle and their message queues are empty. Empty targets waits for all.",
                    Schemas.object(Schemas.properties(
                            "targets", Schemas.array("Member names/ids.", Schemas.string("Member.")),
                            "timeoutSeconds", Schemas.integer("Timeout in seconds.", 1, 1800)
                    )));
        }

        @Override
        ToolResult run(Map<String, Object> args) {
            List<String> targets = strings(args, "targets");
            int timeout = ToolArgs.integer(args, "timeoutSeconds", 600, 1, 1800);
            Map<String, Object> result = team.waitFor(actor, targets, timeout * 1000L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) result.get("members");
            StringBuilder out = new StringBuilder(Boolean.TRUE.equals(result.get("timedOut")) ? "Timed out." : "All idle.");
            for (Map<String, Object> member : members) {
                out.append("\n- ").append(member.get("name")).append(": ")
                        .append(member.get("lastResult") == null || String.valueOf(member.get("lastResult")).isBlank()
                                ? "(no result)" : member.get("lastResult"));
                if (member.get("lastError") != null) out.append(" ERROR ").append(member.get("lastError"));
            }
            if (result.get("messages") instanceof List<?> messages && !messages.isEmpty()) {
                out.append("\nMessages to Lead:");
                for (Object message : messages) out.append("\n- ").append(message);
            }
            return ToolResult.ok(out.toString(), Map.of("timedOut", result.get("timedOut"), "count", members.size()));
        }
    }
}
