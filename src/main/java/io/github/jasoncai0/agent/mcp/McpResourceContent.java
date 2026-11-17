package io.github.jasoncai0.agent.mcp;

/** A single content item returned by {@code resources/read}. */
public record McpResourceContent(String uri, String mimeType, String text, boolean blob) {
}
