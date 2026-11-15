package io.github.jasoncai0.agent.permission;

import java.util.Locale;

public enum PermissionMode {
    BALANCED,
    STRICT,
    READ_ONLY,
    BYPASS;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PermissionMode fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return BALANCED;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "balanced" -> BALANCED;
            case "strict" -> STRICT;
            case "read-only", "readonly", "read_only" -> READ_ONLY;
            case "bypass", "dangerous" -> BYPASS;
            default -> throw new IllegalArgumentException(
                    "AGENT_PERMISSION_MODE must be balanced, strict, read-only, or bypass; got '" + raw + "'");
        };
    }
}
