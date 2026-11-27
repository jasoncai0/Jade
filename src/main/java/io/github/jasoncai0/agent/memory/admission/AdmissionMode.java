package io.github.jasoncai0.agent.memory.admission;

import java.util.Locale;

/** Controls long-term memory write admission strategy. */
public enum AdmissionMode {
    OFF("off"),
    HEURISTIC("heuristic"),
    LLM("llm"),
    HYBRID("hybrid");

    private final String id;

    AdmissionMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static AdmissionMode fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false", "none" -> OFF;
            case "heuristic", "keywords" -> HEURISTIC;
            case "llm" -> LLM;
            case "hybrid", "default", "on" -> HYBRID;
            default -> throw new IllegalArgumentException(
                    "AGENT_MEMORY_ADMISSION must be off, heuristic, llm, or hybrid, got '" + raw.trim() + "'");
        };
    }
}
