package io.github.jasoncai0.agent.mcp;

import java.util.Map;

public record McpToolDefinition(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {
    public McpToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP tool name must not be blank");
        }
        description = description == null ? "" : description.strip();
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
