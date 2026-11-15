package io.github.jasoncai0.agent.permission;

import java.util.Locale;

public enum PermissionScope {
    ONCE,
    SESSION;

    public static PermissionScope fromId(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("once")) {
            return ONCE;
        }
        if (raw.equalsIgnoreCase("session")) {
            return SESSION;
        }
        throw new IllegalArgumentException("scope must be once or session");
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
