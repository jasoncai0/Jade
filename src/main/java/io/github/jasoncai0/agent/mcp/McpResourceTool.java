package io.github.jasoncai0.agent.mcp;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes an MCP server's resources as three read-only Jade tools. The tools
 * are only registered when the server advertises the {@code resources}
 * capability, so servers that only implement tools keep their old surface.
 */
final class McpResourceTool implements Tool {
    enum Kind {
        LIST("list_resources", "List the resources exposed by MCP server '%s'."),
        TEMPLATES("list_resource_templates", "List the RFC 6570 URI templates exposed by MCP server '%s'."),
        READ("read_resource", "Read one resource by URI from MCP server '%s'.");

        private final String suffix;
        private final String description;

        Kind(String suffix, String description) {
            this.suffix = suffix;
            this.description = description;
        }
    }

    private static final int MAX_OUTPUT = 30_000;

    private final McpServerConfig server;
    private final McpConnection connection;
    private final Kind kind;
    private final String jadeName;

    McpResourceTool(McpServerConfig server, McpConnection connection, Kind kind, String jadeName) {
        this.server = server;
        this.connection = connection;
        this.kind = kind;
        this.jadeName = jadeName;
    }

    @Override
    public ToolSpec spec() {
        String description = "[MCP " + server.name() + "] "
                + String.format(kind.description, server.name());
        Map<String, Object> parameters = kind == Kind.READ
                ? Schemas.object(Schemas.properties(
                        "uri", Schemas.string("Resource URI, usually copied from list_resources or a template.")),
                        "uri")
                : Schemas.object(Schemas.properties());
        return new ToolSpec(jadeName, description, parameters);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        try {
            return switch (kind) {
                case LIST -> listResources();
                case TEMPLATES -> listTemplates();
                case READ -> readResource(ToolArgs.requiredString(args, "uri"));
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("MCP " + server.name() + " " + kind.suffix
                    + " failed: " + e.getMessage());
        }
    }

    private ToolResult listResources() {
        List<McpResource> resources = connection.listResources();
        StringBuilder text = new StringBuilder("resources: ").append(resources.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpResource resource : resources) {
            text.append('\n').append("- ").append(resource.uri());
            if (!resource.name().isBlank()) {
                text.append(" (").append(resource.name());
                if (!resource.mimeType().isBlank()) {
                    text.append(", ").append(resource.mimeType());
                }
                text.append(')');
            } else if (!resource.mimeType().isBlank()) {
                text.append(" (").append(resource.mimeType()).append(')');
            }
            if (!resource.description().isBlank()) {
                text.append(": ").append(resource.description());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", resource.uri());
            item.put("name", resource.name());
            item.put("description", resource.description());
            item.put("mimeType", resource.mimeType());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("server", server.name());
        data.put("count", resources.size());
        data.put("resources", items);
        return ToolResult.ok(cap(text.toString()), data);
    }

    private ToolResult listTemplates() {
        List<McpResourceTemplate> templates = connection.listResourceTemplates();
        StringBuilder text = new StringBuilder("resourceTemplates: ").append(templates.size());
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpResourceTemplate template : templates) {
            text.append('\n').append("- ").append(template.uriTemplate());
            if (!template.name().isBlank()) {
                text.append(" (").append(template.name()).append(')');
            }
            if (!template.description().isBlank()) {
                text.append(": ").append(template.description());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uriTemplate", template.uriTemplate());
            item.put("name", template.name());
            item.put("description", template.description());
            item.put("mimeType", template.mimeType());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("server", server.name());
        data.put("count", templates.size());
        data.put("resourceTemplates", items);
        return ToolResult.ok(cap(text.toString()), data);
    }

    private ToolResult readResource(String uri) {
        List<McpResourceContent> contents = connection.readResource(uri);
        if (contents.isEmpty()) {
            return ToolResult.error("MCP " + server.name() + " returned no content for " + uri);
        }
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> items = new ArrayList<>();
        for (McpResourceContent content : contents) {
            if (!text.isEmpty()) {
                text.append("\n---\n");
            }
            text.append(content.text());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uri", content.uri());
            item.put("mimeType", content.mimeType());
            item.put("blob", content.blob());
            item.put("chars", content.text().length());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("server", server.name());
        data.put("uri", uri);
        data.put("contents", items);
        return ToolResult.ok(cap(text.toString()), data);
    }

    private static String cap(String text) {
        return text.length() <= MAX_OUTPUT
                ? text
                : text.substring(0, MAX_OUTPUT) + "\n... [MCP resource output truncated]";
    }

    static String suffix(Kind kind) {
        return kind.suffix;
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }
}
