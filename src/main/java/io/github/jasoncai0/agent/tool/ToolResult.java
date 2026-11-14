package io.github.jasoncai0.agent.tool;

import java.util.Map;

public record ToolResult(boolean ok, String output, Map<String, Object> data) {
    public ToolResult {
        output = output == null ? "" : output;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static ToolResult ok(String output) {
        return new ToolResult(true, output, Map.of());
    }

    public static ToolResult ok(String output, Map<String, Object> data) {
        return new ToolResult(true, output, data);
    }

    public static ToolResult error(String output) {
        return new ToolResult(false, output, Map.of());
    }

    public static ToolResult error(String output, Map<String, Object> data) {
        return new ToolResult(false, output, data);
    }
}
