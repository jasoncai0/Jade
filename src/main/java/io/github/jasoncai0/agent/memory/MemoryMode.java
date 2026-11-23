package io.github.jasoncai0.agent.memory;

import java.util.Locale;

/** Runtime policy for long-term memory. */
public enum MemoryMode {
    ON("on"),
    SHADOW("shadow"),
    OFF("off");

    private final String id;

    MemoryMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean active() {
        return this != OFF;
    }

    public static MemoryMode fromId(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (clean) {
            case "", "on", "true", "1" -> ON;
            case "shadow", "dry-run", "dry_run" -> SHADOW;
            case "off", "false", "0", "disabled" -> OFF;
            default -> throw new IllegalArgumentException(
                    "AGENT_MEMORY_MODE must be on, shadow, or off, got '" + value + "'");
        };
    }
}
