package io.github.jasoncai0.agent.permission;

public record PermissionEvaluation(
        Decision decision,
        PermissionKind kind,
        String risk,
        String summary,
        String detail,
        String target,
        boolean sensitive,
        String host,
        boolean networkAllowed
) {
    public enum Decision {
        ALLOW,
        ASK,
        DENY
    }

    public static PermissionEvaluation allow(String summary) {
        return new PermissionEvaluation(Decision.ALLOW, PermissionKind.COMMAND, "low",
                summary, "", "", false, null, false);
    }

    public static PermissionEvaluation allowNetwork(String summary, String host) {
        return new PermissionEvaluation(Decision.ALLOW, PermissionKind.NETWORK, "medium",
                summary, "", host, false, host, true);
    }

    public static PermissionEvaluation ask(PermissionKind kind, String risk, String summary,
                                           String detail, String target, boolean sensitive,
                                           String host, boolean networkAllowed) {
        return new PermissionEvaluation(Decision.ASK, kind, risk, summary, detail, target,
                sensitive, host, networkAllowed);
    }

    public static PermissionEvaluation deny(String summary) {
        return new PermissionEvaluation(Decision.DENY, PermissionKind.COMMAND, "high",
                summary, "", "", false, null, false);
    }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }
}
