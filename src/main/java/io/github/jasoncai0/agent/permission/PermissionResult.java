package io.github.jasoncai0.agent.permission;

public record PermissionResult(
        String requestId,
        PermissionDecision decision,
        PermissionScope scope,
        String reason,
        boolean networkAllowed
) {
    public static PermissionResult allow(String requestId, PermissionScope scope, String reason,
                                         boolean networkAllowed) {
        return new PermissionResult(requestId, PermissionDecision.ALLOW, scope, reason, networkAllowed);
    }

    public static PermissionResult deny(String requestId, String reason) {
        return new PermissionResult(requestId, PermissionDecision.DENY, PermissionScope.ONCE, reason, false);
    }

    public boolean allowed() {
        return decision == PermissionDecision.ALLOW;
    }
}
