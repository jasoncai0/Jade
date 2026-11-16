package io.github.jasoncai0.agent.question;

import java.util.Locale;

public enum QuestionMode {
    SINGLE,
    MULTIPLE,
    TEXT;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static QuestionMode fromId(String value) {
        if (value == null || value.isBlank()) {
            return SINGLE;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "single", "one" -> SINGLE;
            case "multiple", "multi", "many" -> MULTIPLE;
            case "text", "input", "free" -> TEXT;
            default -> throw new IllegalArgumentException("Unknown question mode: " + value);
        };
    }
}
