package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.WorkspaceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Authorizes, executes (in parallel when all calls are read-only concurrency-safe),
 * spills large outputs, and appends tool messages in model-issued order.
 */
final class ToolBatchExecutor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOOL_OUTPUT_CHARS = 30_000;

    private final ToolRegistry tools;
    private final AgentSettings settings;

    ToolBatchExecutor(ToolRegistry tools, AgentSettings settings) {
        this.tools = tools;
        this.settings = settings;
    }

    static void rejectToolCalls(List<ToolCall> calls, List<ChatMessage> messages, String reason,
                                String guard, RunEventLog log) {
        for (ToolCall call : calls) {
            String output = "Blocked by " + guard + " guard: " + reason;
            messages.add(ChatMessage.tool(call.id(), output));
            log.emit("tool.start", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "arguments", call.arguments()
            ));
            log.emit("tool.end", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "ok", false,
                    "output", output,
                    "durationMs", 0L,
                    "data", Map.of("guard", guard)
            ));
        }
    }

    void execute(List<ToolCall> calls, List<ChatMessage> messages,
                 ToolContext context, RunState state, RunEventLog log,
                 RunControl control, int[] stats, PermissionManager permissions,
                 ToolOutputStore spill) {
        int limit = Math.min(calls.size(), settings.maxToolCallsPerStep());
        List<PreparedCall> prepared = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            control.checkCancelled();
            ToolCall call = calls.get(i);
            PreparedCall item = new PreparedCall(call);
            prepared.add(item);
            if (i >= limit) {
                item.error = "Skipped: the limit is " + settings.maxToolCallsPerStep()
                        + " tool calls per model turn. Ask for this tool again in the next turn.";
                continue;
            }
            try {
                item.args = parseArguments(call.arguments());
            } catch (RuntimeException e) {
                item.error = "Invalid JSON arguments: " + e.getMessage();
                continue;
            }
            log.emit("tool.start", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "arguments", truncate(call.arguments(), 5000)
            ));
            item.permission = permissions.authorize(call.name(), item.args);
            if (!item.permission.allowed()) {
                item.error = "Permission denied: " + item.permission.reason();
            }
        }

        long executable = prepared.stream().filter(PreparedCall::executable).count();
        boolean canParallel = executable >= 2
                && prepared.stream().filter(PreparedCall::executable)
                        .allMatch(item -> tools.concurrencySafe(item.call.name())
                                && !item.permission.networkAllowed());
        if (canParallel) {
            runParallel(prepared, context, control);
        } else {
            for (PreparedCall item : prepared) {
                if (!item.executable()) {
                    continue;
                }
                context.setNetworkAllowed(item.permission.networkAllowed());
                long started = System.currentTimeMillis();
                try {
                    item.result = tools.execute(item.call.name(), item.args, context);
                } finally {
                    context.setNetworkAllowed(false);
                }
                item.duration = System.currentTimeMillis() - started;
            }
        }

        for (PreparedCall item : prepared) {
            if (!item.executable()) {
                messages.add(ChatMessage.tool(item.call.id(),
                        "ERROR " + item.call.name() + ": " + item.error));
                log.emit("tool.end", eventMap("id", item.call.id(), "name", item.call.name(),
                        "ok", false, "output", item.error, "durationMs", 0));
                stats[1]++;
                continue;
            }
            ToolResult result = item.result;
            if (result == null) {
                result = ToolResult.error("Tool " + item.call.name() + " was cancelled before it ran");
            }
            String output;
            try {
                ToolOutputRef ref = spill.save(state.id(), log.lastSeq(), item.call.name(), item.call.id(), result.output());
                output = spill.inline(result.output(), ref);
            } catch (WorkspaceException e) {
                output = truncate(result.output(), MAX_TOOL_OUTPUT_CHARS)
                        + "\n[spill failed: " + e.getMessage() + "]";
            }
            messages.add(ChatMessage.tool(item.call.id(),
                    (result.ok() ? "OK " : "ERROR ") + item.call.name() + "\n" + output));
            if (result.ok()) {
                stats[0]++;
            } else {
                stats[1]++;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", item.call.id());
            event.put("name", item.call.name());
            event.put("ok", result.ok());
            event.put("output", output);
            event.put("durationMs", item.duration);
            event.put("data", result.data());
            log.emit("tool.end", event);
            if (item.call.name().equals("update_plan")) {
                log.emit("plan", eventMap("plan", state.plan()));
            }
            Log.info("agent.tool", Map.of(
                    "runId", state.id(),
                    "tool", item.call.name(),
                    "ok", result.ok(),
                    "durationMs", item.duration
            ));
        }
        context.refreshFiles();
        control.checkCancelled();
    }

    private void runParallel(List<PreparedCall> prepared, ToolContext context, RunControl control) {
        List<PreparedCall> runnable = prepared.stream().filter(PreparedCall::executable).toList();
        int workers = Math.min(4, runnable.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (PreparedCall item : runnable) {
                if (control.isCancelled()) {
                    break;
                }
                futures.add(pool.submit(() -> {
                    if (control.isCancelled()) {
                        return;
                    }
                    long started = System.currentTimeMillis();
                    try {
                        item.result = tools.execute(item.call.name(), item.args, context);
                    } catch (RuntimeException e) {
                        item.result = ToolResult.error("Tool " + item.call.name() + " failed: " + e.getMessage());
                    }
                    item.duration = System.currentTimeMillis() - started;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RunControl.RunCancelledException();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Parallel tool worker failed", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
            boolean interrupted = false;
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        control.checkCancelled();
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n... [truncated]";
    }

    private static Map<String, Object> eventMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    private static final class PreparedCall {
        private final ToolCall call;
        private Map<String, Object> args;
        private PermissionResult permission;
        private String error;
        private ToolResult result;
        private long duration;

        private PreparedCall(ToolCall call) {
            this.call = call;
        }

        private boolean executable() {
            return error == null && permission != null && permission.allowed();
        }
    }
}
