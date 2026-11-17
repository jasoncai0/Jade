package io.github.jasoncai0.agent.mcp;

/** One RFC 6570 URI template advertised by an MCP server. */
public record McpResourceTemplate(String uriTemplate, String name, String description, String mimeType) {
}
