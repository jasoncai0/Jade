package io.github.jasoncai0.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionPolicyTest {
    @Test
    void balancedAsksForFirstWriteButAllowsSessionGrant() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.BALANCED);
        PermissionSession session = new PermissionSession();
        Map<String, Object> args = Map.of("path", "src/Main.java", "content", "class Main {}");

        assertEquals(PermissionEvaluation.Decision.ASK,
                policy.evaluate("write_file", args, session).decision());
        session.grant(request(PermissionKind.FILE_WRITE, "src/Main.java"), PermissionScope.SESSION);
        assertEquals(PermissionEvaluation.Decision.ALLOW,
                policy.evaluate("write_file", args, session).decision());
    }

    @Test
    void sensitivePathsAreNeverCoveredBySessionGrant() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.BALANCED);
        PermissionSession session = new PermissionSession();
        session.grant(request(PermissionKind.FILE_WRITE, "src/Main.java"), PermissionScope.SESSION);

        PermissionEvaluation evaluation = policy.evaluate("write_file",
                Map.of("path", ".env", "content", "SECRET=1"), session);

        assertEquals(PermissionEvaluation.Decision.ASK, evaluation.decision());
        assertTrue(evaluation.sensitive());
    }

    @Test
    void readOnlyDeniesWritesAndExecutionButAllowsReads() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.READ_ONLY);
        PermissionSession session = new PermissionSession();

        assertEquals(PermissionEvaluation.Decision.DENY,
                policy.evaluate("run_command", Map.of("command", "ls"), session).decision());
        assertEquals(PermissionEvaluation.Decision.DENY,
                policy.evaluate("run_code", Map.of("entry", "main.py"), session).decision());
        assertEquals(PermissionEvaluation.Decision.ALLOW,
                policy.evaluate("read_file", Map.of("path", "README.md"), session).decision());
    }

    @Test
    void hardDenyWinsEvenInBypassMode() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.BYPASS);
        PermissionSession session = new PermissionSession();

        PermissionEvaluation evaluation = policy.evaluate("run_command",
                Map.of("command", "sudo rm -rf /"), session);

        assertEquals(PermissionEvaluation.Decision.DENY, evaluation.decision());
    }

    @Test
    void networkAskCarriesTheHostAndNetworkPermission() {
        PermissionPolicy policy = new PermissionPolicy(PermissionMode.BALANCED);
        PermissionEvaluation evaluation = policy.evaluate("run_command",
                Map.of("command", "curl https://example.com/data"), new PermissionSession());

        assertEquals(PermissionEvaluation.Decision.ASK, evaluation.decision());
        assertEquals(PermissionKind.NETWORK, evaluation.kind());
        assertEquals("example.com", evaluation.host());
        assertTrue(evaluation.networkAllowed());
    }

    private static PermissionRequest request(PermissionKind kind, String target) {
        return PermissionRequest.create("run-test", "write_file", kind, "low",
                "test request", "", target, 300_000);
    }
}
