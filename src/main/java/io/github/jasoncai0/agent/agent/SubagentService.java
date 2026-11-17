package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.llm.Usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs one bounded, read-only child investigation on behalf of a parent turn.
 * The child has its own transcript, event log and step budget; the parent only
 * receives a compact summary, so a long exploration cannot flood its context.
 *
 * <p>Concurrency is capped per parent and depth is fixed at one: the child tool
 * registry does not contain {@code delegate_task}, and a child state is marked
 * as a subagent so a direct call is rejected as well.
 */
public final class SubagentService implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_ACTIVE_PER_PARENT = 2;
    public static final int MAX_DEPTH = 1;
    public static final int MAX_TASK_CHARS = 8_000;
    public static final int MAX_SUMMARY_CHARS = 8_000;
    public static final int MAX_FILES_READ = 20;
    public static final int MAX_EVENTS_SCANNED = 20_000;
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "list_files", "read_file", "search_text", "load_skill", "reflect");

    public record DelegationResult(String childRunId, String status, String summary,
                                   Usage usage, List<String> filesRead) {
        public DelegationResult {
            summary = summary == null ? "" : summary;
            usage = usage == null ? Usage.ZERO : usage;
            filesRead = filesRead == null ? List.of() : List.copyOf(filesRead);
        }
    }

    private final AgentService service;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();
    private final Map<String, AgentRun> active = new ConcurrentHashMap<>();

    SubagentService(AgentService service) {
        this.service = service;
    }

    /** Tool names exposed to a child; deliberately excludes every writer. */
    public static Set<String> readOnlyToolNames() {
        return READ_ONLY_TOOLS;
    }

    public DelegationResult start(AgentRun parent, String task, String name, int timeoutSeconds) {
        if (parent == null) {
            throw new IllegalArgumentException("A parent run is required");
        }
        if (parent.state().isSubagent()) {
            throw new IllegalArgumentException("A subagent cannot delegate another task (max depth is 1)");
        }
        String cleanTask = task == null ? "" : task.trim();
        if (cleanTask.isEmpty() || cleanTask.length() > MAX_TASK_CHARS) {
            throw new IllegalArgumentException("task must be 1.." + MAX_TASK_CHARS + " characters");
        }
        int timeout = Math.max(1, Math.min(timeoutSeconds, 900));
        Semaphore slot = slots.computeIfAbsent(parent.id(),
                ignored -> new Semaphore(MAX_ACTIVE_PER_PARENT));
        boolean acquired = false;
        try {
            acquired = slot.tryAcquire(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DelegationResult("", "cancelled", "Interrupted while waiting for a subagent slot",
                    Usage.ZERO, List.of());
        }
        if (!acquired) {
            return new DelegationResult("", "timeout",
                    "No subagent slot became available within " + timeout + "s",
                    Usage.ZERO, List.of());
        }

        AgentRun child = null;
        try {
            child = service.newChildRun(parent, name, cleanTask);
            active.put(child.id(), child);
            Map<String, Object> startData = new LinkedHashMap<>();
            startData.put("childRunId", child.id());
            startData.put("name", name == null || name.isBlank() ? "subagent" : name);
            startData.put("task", cleanTask);
            parent.events().emit("subagent.start", startData);

            CompletableFuture<RunSnapshot> done = new CompletableFuture<>();
            RunControl control = new RunControl();
            AgentRun running = child;
            Future<?> future = executor.submit(() -> service.runChild(running, cleanTask, control, done));

            long deadline = System.currentTimeMillis() + timeout * 1000L;
            RunSnapshot snapshot;
            while (true) {
                if (parent.control().isCancelled()) {
                    control.cancel();
                    future.cancel(true);
                    return finish(parent, child, "cancelled",
                            "Parent run was cancelled", Usage.ZERO, List.of());
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    control.cancel();
                    future.cancel(true);
                    return finish(parent, child, "timeout",
                            "Subagent exceeded its " + timeout + "s budget", Usage.ZERO, List.of());
                }
                try {
                    snapshot = done.get(Math.min(250L, remaining), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    // Keep polling so parent cancellation is observed promptly.
                }
            }

            String status = snapshot.status().toLowerCase();
            String summary = snapshot.finalAnswer();
            if (summary == null || summary.isBlank()) {
                summary = snapshot.error() == null ? "Subagent finished without a summary" : snapshot.error();
            }
            List<String> filesRead = filesRead(child);
            return finish(parent, child, status, summary, snapshot.usage(), filesRead);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (child != null) {
                child.control().cancel();
            }
            return new DelegationResult(child == null ? "" : child.id(), "cancelled",
                    "Interrupted while waiting for the subagent", Usage.ZERO, List.of());
        } catch (ExecutionException e) {
            String message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            return finish(parent, child, "failed",
                    message == null ? "Subagent failed" : message, Usage.ZERO, List.of());
        } catch (RuntimeException e) {
            return finish(parent, child, "failed",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    Usage.ZERO, List.of());
        } finally {
            if (child != null) {
                active.remove(child.id());
            }
            slot.release();
        }
    }

    private DelegationResult finish(AgentRun parent, AgentRun child, String status,
                                    String summary, Usage usage, List<String> filesRead) {
        String capped = summary.length() <= MAX_SUMMARY_CHARS
                ? summary : summary.substring(0, MAX_SUMMARY_CHARS) + "\n... [summary truncated]";
        if (child != null) {
            parent.state().addSubagentUsage(usage);
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("childRunId", child.id());
            end.put("status", status);
            end.put("tokens", usage.totalTokens());
            end.put("filesRead", filesRead.size());
            parent.events().emit("subagent.end", end);
        }
        Log.info("subagent.finished", Map.of(
                "parentRunId", parent.id(),
                "childRunId", child == null ? "" : child.id(),
                "status", status));
        return new DelegationResult(child == null ? "" : child.id(), status, capped, usage, filesRead);
    }

    private List<String> filesRead(AgentRun child) {
        List<String> paths = new ArrayList<>();
        List<AgentEvent> events;
        try {
            events = child.events().snapshot();
        } catch (RuntimeException e) {
            return paths;
        }
        int scanned = 0;
        for (AgentEvent event : events) {
            if (++scanned > MAX_EVENTS_SCANNED || paths.size() >= MAX_FILES_READ) {
                break;
            }
            if (!"tool.start".equals(event.type())) {
                continue;
            }
            Object name = event.data().get("name");
            if (!"read_file".equals(name) && !"list_files".equals(name)) {
                continue;
            }
            Object arguments = event.data().get("arguments");
            Map<String, Object> map = null;
            if (arguments instanceof Map<?, ?> raw) {
                map = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    map.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (arguments instanceof String text && !text.isBlank()) {
                try {
                    map = JSON.readValue(text, new TypeReference<Map<String, Object>>() { });
                } catch (Exception ignored) {
                    map = null;
                }
            }
            if (map != null) {
                Object path = map.get("path");
                if (path instanceof String text && !text.isBlank() && !paths.contains(text)) {
                    paths.add(text);
                }
            }
        }
        return paths;
    }

    /** Cancels every live child belonging to a parent run. */
    void cancelChildren(String parentId) {
        for (AgentRun child : active.values()) {
            if (parentId.equals(child.state().parentRunId())) {
                child.control().cancel();
            }
        }
    }

    int activeCount() {
        return active.size();
    }

    @Override
    public void close() {
        for (AgentRun child : active.values()) {
            child.control().cancel();
        }
        active.clear();
        executor.shutdownNow();
    }
}
