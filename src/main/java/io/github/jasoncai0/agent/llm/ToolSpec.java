package io.github.jasoncai0.agent.llm;

import java.util.Map;

public record ToolSpec(String name, String description, Map<String, Object> parameters, boolean strict) {
    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        parameters = parameters == null ? Map.of() : parameters;
    }

    /**
     * Backwards-compatible constructor for non-strict tools.
     */
    public ToolSpec(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, false);
    }
}
