package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionEvaluation;
import io.github.jasoncai0.agent.permission.PermissionKind;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.permission.PermissionPolicy;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionScope;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the permission policy and blocks one tool call until the UI/CLI
 * resolves it. A denial is a tool error, not an immediate run failure.
 */
public final class PermissionManager {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration APPROVAL_TIMEOUT = Duration.ofSeconds(300);

    private final PermissionPolicy policy;
    private final PermissionSession session;
    private final PermissionBroker broker;
    private final RunState state;
    private final RunEventLog log;
    private final RunControl control;
    private final Workspace workspace;

    public PermissionManager(PermissionMode mode, PermissionSession session,
                             PermissionBroker broker, RunState state, RunEventLog log,
                             RunControl control, Workspace workspace) {
        this.policy = new PermissionPolicy(mode);
        this.session = session == null ? new PermissionSession() : session;
        this.broker = broker;
        this.state = state;
        this.log = log;
        this.control = control;
        this.workspace = workspace;
    }

    public PermissionResult authorize(String tool, Map<String, Object> args) {
        PermissionEvaluation evaluation = policy.evaluate(tool, args == null ? Map.of() : args, session);
        if (evaluation.decision() == PermissionEvaluation.Decision.DENY) {
            audit(null, PermissionResult.deny("policy", evaluation.summary()));
            return PermissionResult.deny("policy", evaluation.summary());
        }
        if (evaluation.decision() == PermissionEvaluation.Decision.ALLOW) {
            return PermissionResult.allow("policy", PermissionScope.ONCE,
                    "allowed by " + policy.mode().id(), evaluation.networkAllowed());
        }

        PermissionRequest request = PermissionRequest.create(
                state.id(), tool, evaluation.kind(), evaluation.risk(),
                evaluation.summary(), evaluation.detail(), evaluation.target(),
                APPROVAL_TIMEOUT.toMillis());
        broker.register(request);
        state.awaitPermission(request);
        log.emit("permission.request", request.toEventData());
        Log.info("permission.request", Map.of(
                "runId", state.id(), "id", request.id(), "tool", tool, "kind", request.kind().name()));

        PermissionResult result;
        try {
            result = broker.await(request, APPROVAL_TIMEOUT);
        } catch (RuntimeException e) {
            result = PermissionResult.deny(request.id(), "approval failed");
        } finally {
            state.resolvePermission();
        }
        if (result.allowed() && result.scope() == PermissionScope.SESSION) {
            session.grant(request, PermissionScope.SESSION);
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("id", request.id());
        resolved.put("decision", result.allowed() ? "allow" : "deny");
        resolved.put("scope", result.scope().id());
        resolved.put("reason", result.reason());
        log.emit("permission.resolved", resolved);
        audit(request, result);
        Log.info("permission.resolved", Map.of(
                "runId", state.id(), "id", request.id(),
                "decision", result.allowed() ? "allow" : "deny", "scope", result.scope().id()));
        if (control.isCancelled()) {
            throw new RunControl.RunCancelledException();
        }
        return result;
    }

    private void audit(PermissionRequest request, PermissionResult result) {
        try {
            Path file = workspace.root().resolve(".agent/permissions.jsonl");
            Files.createDirectories(file.getParent());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", System.currentTimeMillis());
            row.put("requestId", request == null ? "" : request.id());
            row.put("tool", request == null ? "policy" : request.tool());
            row.put("kind", request == null ? "POLICY" : request.kind().name());
            row.put("decision", result.allowed() ? "allow" : "deny");
            row.put("scope", result.scope().id());
            row.put("summary", request == null ? result.reason() : request.summary());
            String line = JSON.writeValueAsString(row) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            Log.warn("permission.audit_failed", Map.of("runId", state.id(), "error", String.valueOf(e.getMessage())));
        }
    }
}
