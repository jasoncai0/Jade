package io.github.jasoncai0.agent.team;

import java.util.Locale;

public enum TeamTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    DELETED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean active() {
        return this == PENDING || this == IN_PROGRESS;
    }

    public static TeamTaskStatus fromId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "pending" -> PENDING;
            case "in_progress", "in-progress", "started" -> IN_PROGRESS;
            case "completed", "done" -> COMPLETED;
            case "deleted", "removed" -> DELETED;
            default -> throw new IllegalArgumentException("unknown task status: " + value);
        };
    }
}
