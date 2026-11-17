package io.github.jasoncai0.agent.mcp;

import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connects configured MCP servers, exposes their tools, and reports status. */
public final class McpManager implements AutoCloseable {
    private final List<McpConnection> connections = new ArrayList<>();
    private static final int HTTP_CONNECT_ATTEMPTS = 5;
    private static final long HTTP_RETRY_INITIAL_MS = 500;
    private static final long HTTP_RETRY_MAX_MS = 30_000;

    private final List<Map<String, Object>> serverStatus = new ArrayList<>();
    private int toolCount;
    private boolean configured;

    private McpManager() {
    }

    public static McpManager connect(Path projectRoot, ToolRegistry registry) {
        McpConfig config = McpConfig.load(projectRoot);
        return connect(config, registry, projectRoot);
    }

    static McpManager connect(McpConfig config, ToolRegistry registry, Path projectRoot) {
        McpManager manager = new McpManager();
        manager.configured = !config.servers().isEmpty();
        for (McpServerConfig server : config.servers()) {
            McpConnection connection = null;
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("name", server.name());
            status.put("transport", server.transport());
            int serverTools = 0;
            try {
                if (!server.enabled()) {
                    status.put("connected", false);
                    status.put("error", "disabled");
                    manager.serverStatus.add(status);
                    continue;
                }
                connection = connectWithRetry(server, projectRoot, status);
                List<McpToolDefinition> definitions = connection.listTools();
                for (McpToolDefinition definition : definitions) {
                    int attempt = 1;
                    String name = McpTool.toolName(server.name(), definition.name(), attempt);
                    while (registry.find(name).isPresent() && attempt < 50) {
                        attempt++;
                        name = McpTool.toolName(server.name(), definition.name(), attempt);
                    }
                    if (registry.find(name).isPresent()) {
                        continue;
                    }
                    registry.register(new McpTool(server, connection, definition, name));
                    serverTools++;
                }
                if (connection.resourcesSupported()) {
                    int resourceTools = 0;
                    for (McpResourceTool.Kind kind : McpResourceTool.Kind.values()) {
                        String suffix = McpResourceTool.suffix(kind);
                        int attempt = 1;
                        String name = McpTool.toolName(server.name(), suffix, attempt);
                        while (registry.find(name).isPresent() && attempt < 50) {
                            attempt++;
                            name = McpTool.toolName(server.name(), suffix, attempt);
                        }
                        if (registry.find(name).isPresent()) {
                            continue;
                        }
                        registry.register(new McpResourceTool(server, connection, kind, name));
                        resourceTools++;
                    }
                    serverTools += resourceTools;
                    status.put("resourceTools", resourceTools);
                }
                manager.connections.add(connection);
                manager.toolCount += serverTools;
                status.put("connected", true);
                status.put("tools", serverTools);
                Log.info("mcp.server.connected", Map.of(
                        "server", server.name(),
                        "transport", server.transport(),
                        "tools", serverTools));
            } catch (RuntimeException e) {
                if (connection != null) {
                    connection.close();
                }
                status.put("connected", false);
                status.put("tools", serverTools);
                status.put("error", String.valueOf(e.getMessage()));
                Log.warn("mcp.server.failed", Map.of(
                        "server", server.name(),
                        "error", String.valueOf(e.getMessage())));
            }
            manager.serverStatus.add(status);
        }
        return manager;
    }

    private static McpConnection connectWithRetry(McpServerConfig server, Path projectRoot,
                                                  Map<String, Object> status) {
        int attempts = server.stdio() ? 1 : HTTP_CONNECT_ATTEMPTS;
        long delay = HTTP_RETRY_INITIAL_MS;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            McpConnection connection = server.stdio()
                    ? new StdioMcpConnection(server, projectRoot)
                    : new HttpMcpConnection(server);
            try {
                connection.initialize();
                status.put("attempts", attempt);
                return connection;
            } catch (RuntimeException e) {
                last = e;
                connection.close();
                status.put("attempts", attempt);
                if (attempt == attempts || !retryable(e)) {
                    throw e;
                }
                Log.warn("mcp.server.retry", Map.of(
                        "server", server.name(),
                        "attempt", attempt,
                        "delayMs", delay,
                        "error", String.valueOf(e.getMessage())));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new McpException("MCP connect interrupted for '" + server.name() + "'", interrupted);
                }
                delay = Math.min(HTTP_RETRY_MAX_MS, delay * 2);
            }
        }
        throw last == null ? new McpException("MCP connect failed for '" + server.name() + "'") : last;
    }

    private static boolean retryable(RuntimeException error) {
        String message = String.valueOf(error.getMessage());
        if (message.contains("Invalid MCP URL")) {
            return false;
        }
        return !message.matches(".*HTTP 4\\d\\d.*");
    }

    public boolean configured() {
        return configured;
    }

    public int serverCount() {
        return serverStatus.size();
    }

    public int toolCount() {
        return toolCount;
    }

    public List<Map<String, Object>> status() {
        return List.copyOf(serverStatus);
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured);
        result.put("serverCount", serverStatus.size());
        result.put("toolCount", toolCount);
        result.put("servers", status());
        return result;
    }

    @Override
    public void close() {
        for (McpConnection connection : connections) {
            try {
                connection.close();
            } catch (RuntimeException e) {
                Log.warn("mcp.server.close_failed", Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        connections.clear();
    }
}
