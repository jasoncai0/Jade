package io.github.jasoncai0.agent.mcp;

import java.util.List;
import java.util.Map;

/**
 * One MCP server declaration. Both stdio and Streamable HTTP transports are
 * supported; credentials are supplied through environment expansion so they
 * do not have to live in the config file.
 */
public record McpServerConfig(
        String name,
        String transport,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        int timeoutSeconds,
        boolean enabled
) {
    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server name must not be blank");
        }
        name = name.strip();
        transport = transport == null || transport.isBlank() ? "stdio" : transport.strip().toLowerCase();
        if (!transport.equals("stdio") && !transport.equals("http")) {
            throw new IllegalArgumentException("MCP transport for '" + name + "' must be stdio or http");
        }
        command = command == null ? "" : command.strip();
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        url = url == null ? "" : url.strip();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (transport.equals("stdio") && command.isEmpty()) {
            throw new IllegalArgumentException("MCP stdio server '" + name + "' needs a command");
        }
        if (transport.equals("http") && url.isEmpty()) {
            throw new IllegalArgumentException("MCP http server '" + name + "' needs a url");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new IllegalArgumentException("MCP timeout must be 1..600 seconds");
        }
    }

    public boolean stdio() {
        return transport.equals("stdio");
    }
}
