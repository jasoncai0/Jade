package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionKind;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionBrokerTest {
    @Test
    void resolvesOnceWithAllow() {
        PermissionBroker broker = new PermissionBroker();
        PermissionRequest request = request("run-a", PermissionKind.FILE_WRITE, "src/Main.java");
        broker.register(request);

        PermissionResult result = broker.resolve(request.id(), PermissionDecision.ALLOW,
                PermissionScope.ONCE, "test");

        assertNotNull(result);
        assertTrue(result.allowed());
        assertFalse(result.networkAllowed());
        assertFalse(broker.pending().contains(request));
    }

    @Test
    void timeoutDeniesAndLeavesNoPending() {
        PermissionBroker broker = new PermissionBroker();
        PermissionRequest request = request("run-b", PermissionKind.COMMAND, "rm -rf build");
        broker.register(request);

        PermissionResult result = broker.await(request, Duration.ofMillis(30));

        assertFalse(result.allowed());
        assertTrue(broker.pending().isEmpty());
    }

    private static PermissionRequest request(String runId, PermissionKind kind, String target) {
        return PermissionRequest.create(runId, "run_command", kind, "medium",
                "test request", "", target, 300_000);
    }
}
