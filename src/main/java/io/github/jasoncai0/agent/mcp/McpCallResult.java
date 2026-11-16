package io.github.jasoncai0.agent.mcp;

import java.util.Map;

public record McpCallResult(boolean error, String text, Map<String, Object> data) {
    public McpCallResult {
        text = text == null ? "" : text;
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
