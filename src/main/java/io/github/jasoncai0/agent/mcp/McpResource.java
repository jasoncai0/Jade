package io.github.jasoncai0.agent.mcp;

/** One concrete resource advertised by an MCP server. */
public record McpResource(String uri, String name, String description, String mimeType) {
}
