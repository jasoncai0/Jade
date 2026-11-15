package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionScope;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Coordinates approval requests between a blocked agent thread and the Web/CLI
 * UI. Each request has one future and one decision.
 */
public final class PermissionBroker {
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public void register(PermissionRequest request) {
        if (closed) {
            throw new IllegalStateException("Permission broker is closed");
        }
        pending.put(request.id(), new Pending(request, new CompletableFuture<>()));
    }

    public PermissionResult await(PermissionRequest request, Duration timeout) {
        Pending item = pending.get(request.id());
        if (item == null) {
            return PermissionResult.deny(request.id(), "request is no longer pending");
        }
        try {
            return item.future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PermissionResult.deny(request.id(), "interrupted");
        } catch (ExecutionException e) {
            return PermissionResult.deny(request.id(), "approval failed: " + e.getMessage());
        } catch (TimeoutException e) {
            return PermissionResult.deny(request.id(), "timeout");
        } finally {
            pending.remove(request.id());
        }
    }

    public PermissionResult resolve(String requestId, PermissionDecision decision,
                                    PermissionScope scope, String reason) {
        Pending item = pending.remove(requestId);
        if (item == null) {
            return null;
        }
        boolean network = decision == PermissionDecision.ALLOW
                && item.request.kind() == io.github.jasoncai0.agent.permission.PermissionKind.NETWORK;
        PermissionResult result = new PermissionResult(requestId, decision,
                scope == null ? PermissionScope.ONCE : scope,
                reason == null || reason.isBlank() ? "user" : reason, network);
        item.future.complete(result);
        return result;
    }

    public PermissionRequest pendingFor(String runId) {
        return pending.values().stream()
                .map(item -> item.request)
                .filter(request -> request.runId().equals(runId))
                .findFirst()
                .orElse(null);
    }

    public List<PermissionRequest> pending() {
        List<PermissionRequest> result = new ArrayList<>();
        pending.values().forEach(item -> result.add(item.request));
        return result;
    }

    public void cancelAll(String reason) {
        for (String id : List.copyOf(pending.keySet())) {
            resolve(id, PermissionDecision.DENY, PermissionScope.ONCE, reason);
        }
    }

    public void close() {
        closed = true;
        cancelAll("broker closed");
    }

    private record Pending(PermissionRequest request, CompletableFuture<PermissionResult> future) {
    }
}
