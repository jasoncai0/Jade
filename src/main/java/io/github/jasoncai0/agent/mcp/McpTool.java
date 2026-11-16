package io.github.jasoncai0.agent.mcp;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Exposes one MCP server tool as a Jade tool. */
final class McpTool implements Tool {
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1_200;
    private static final int MAX_OUTPUT = 30_000;

    private final McpServerConfig server;
    private final McpConnection connection;
    private final McpToolDefinition definition;
    private final String jadeName;

    McpTool(McpServerConfig server, McpConnection connection, McpToolDefinition definition, String jadeName) {
        this.server = server;
        this.connection = connection;
        this.definition = definition;
        this.jadeName = jadeName;
    }

    @Override
    public ToolSpec spec() {
        String description = "[MCP " + server.name() + "] " + definition.description();
        if (description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION - 3) + "...";
        }
        Map<String, Object> parameters = new LinkedHashMap<>(definition.inputSchema());
        if (!parameters.containsKey("type")) {
            parameters.put("type", "object");
        }
        return new ToolSpec(jadeName, description, parameters);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        try {
            McpCallResult result = connection.callTool(definition.name(), args);
            String output = result.text();
            if (output.length() > MAX_OUTPUT) {
                output = output.substring(0, MAX_OUTPUT)
                        + "\n... [MCP output truncated at " + MAX_OUTPUT + " chars]";
            }
            Map<String, Object> data = new LinkedHashMap<>(result.data());
            data.put("readOnly", definition.readOnly());
            return result.error()
                    ? ToolResult.error(output, data)
                    : ToolResult.ok(output, data);
        } catch (RuntimeException e) {
            return ToolResult.error("MCP " + server.name() + " tool '" + definition.name()
                    + "' failed: " + e.getMessage());
        }
    }

    static String toolName(String server, String tool, int attempt) {
        String base = "mcp_" + sanitize(server) + "_" + sanitize(tool);
        if (attempt > 1) {
            base += "_" + attempt;
        }
        if (base.length() <= MAX_NAME) {
            return base;
        }
        String hash = Integer.toHexString((server + "\u0000" + tool).hashCode());
        int keep = MAX_NAME - hash.length() - 5;
        return base.substring(0, Math.max(5, keep)) + "_" + hash;
    }

    private static String sanitize(String value) {
        String normalized = value == null ? "tool" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "tool" : normalized;
    }
}
