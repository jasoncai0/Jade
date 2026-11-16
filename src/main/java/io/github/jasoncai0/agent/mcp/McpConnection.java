package io.github.jasoncai0.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client protocol implementation shared by stdio and Streamable
 * HTTP. We intentionally keep the transport and protocol small instead of
 * pulling a second JSON stack into the application.
 */
public abstract class McpConnection implements AutoCloseable {
    public static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOOLS = 200;

    protected final McpServerConfig config;
    private final AtomicLong ids = new AtomicLong(1);
    protected volatile String protocolVersion = DEFAULT_PROTOCOL;
    protected volatile boolean initialized;

    protected McpConnection(McpServerConfig config) {
        this.config = config;
    }

    /** Sends a JSON-RPC request and returns its {@code result} object. */
    protected abstract JsonNode send(JsonNode request);

    /** Sends a JSON-RPC notification; the server must not answer. */
    protected abstract void notify(JsonNode notification);

    protected void onInitialized(String negotiatedVersion) {
    }

    public final synchronized void initialize() {
        if (initialized) {
            return;
        }
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", DEFAULT_PROTOCOL);
        params.set("capabilities", JSON.createObjectNode());
        ObjectNode client = JSON.createObjectNode();
        client.put("name", "jade");
        client.put("version", "1.0.0");
        params.set("clientInfo", client);

        JsonNode result = call("initialize", params);
        String negotiated = result.path("protocolVersion").asText(DEFAULT_PROTOCOL);
        protocolVersion = negotiated == null || negotiated.isBlank() ? DEFAULT_PROTOCOL : negotiated;
        onInitialized(protocolVersion);

        ObjectNode notification = JSON.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", JSON.createObjectNode());
        notify(notification);
        initialized = true;
    }

    public final List<McpToolDefinition> listTools() {
        ensureInitialized();
        List<McpToolDefinition> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = JSON.createObjectNode();
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }
            JsonNode result = call("tools/list", params);
            JsonNode items = result.path("tools");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (tools.size() >= MAX_TOOLS) {
                        break;
                    }
                    try {
                        tools.add(parseTool(item));
                    } catch (RuntimeException ignored) {
                        // One malformed server tool must not hide the rest.
                    }
                }
            }
            cursor = result.path("nextCursor").asText("");
        } while (!cursor.isBlank() && tools.size() < MAX_TOOLS);
        return tools;
    }

    public final McpCallResult callTool(String name, Map<String, Object> arguments) {
        ensureInitialized();
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", JSON.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = call("tools/call", params);
        return parseCallResult(name, result);
    }

    protected final JsonNode call(String method, JsonNode params) {
        long id = ids.getAndIncrement();
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        JsonNode response = send(request);
        if (response == null || response.isNull()) {
            throw new McpException("MCP server '" + config.name() + "' returned no response to " + method);
        }
        JsonNode error = response.path("error");
        if (error.isObject()) {
            int code = error.path("code").asInt(-1);
            String message = error.path("message").asText("unknown JSON-RPC error");
            throw new McpException("MCP " + config.name() + " " + method
                    + " failed (" + code + "): " + message);
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode()) {
            throw new McpException("MCP " + config.name() + " response to " + method + " has no result");
        }
        return result;
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    private McpToolDefinition parseTool(JsonNode node) {
        String name = node.path("name").asText("").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("tool has no name");
        }
        String description = node.path("description").asText("").strip();
        JsonNode schema = node.path("inputSchema");
        Map<String, Object> inputSchema = schema.isObject()
                ? JSON.convertValue(schema, new TypeReference<>() {
        })
                : Map.of("type", "object", "properties", Map.of());
        boolean readOnly = node.path("annotations").path("readOnlyHint").asBoolean(false);
        return new McpToolDefinition(name, description, inputSchema, readOnly);
    }

    private McpCallResult parseCallResult(String name, JsonNode result) {
        boolean error = result.path("isError").asBoolean(false);
        List<String> parts = new ArrayList<>();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("");
                if (type.equals("text")) {
                    String text = item.path("text").asText("");
                    if (!text.isBlank()) {
                        parts.add(text);
                    }
                } else if (type.equals("image")) {
                    String mime = item.path("mimeType").asText("image");
                    int bytes = item.path("data").asText("").length();
                    parts.add("[image " + mime + ", base64 chars=" + bytes + "]");
                } else if (type.equals("resource")) {
                    String uri = item.path("resource").path("uri").asText("");
                    String text = item.path("resource").path("text").asText("");
                    if (!text.isBlank()) {
                        parts.add(text);
                    } else {
                        parts.add("[resource " + uri + "]");
                    }
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("server", config.name());
        data.put("tool", name);
        data.put("isError", error);
        JsonNode structured = result.path("structuredContent");
        if (!structured.isMissingNode() && !structured.isNull()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> value = JSON.convertValue(structured, Map.class);
                data.put("structuredContent", value);
                if (parts.isEmpty()) {
                    parts.add(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
                }
            } catch (Exception e) {
                data.put("structuredContent", structured.toString());
            }
        }
        String text = parts.isEmpty() ? "(no MCP content)" : String.join("\n", parts);
        return new McpCallResult(error, text, data);
    }

    public McpServerConfig config() {
        return config;
    }

    @Override
    public abstract void close();


    protected static ObjectMapper mapper() {
        return JSON;
    }

    protected static ObjectNode object() {
        return JSON.createObjectNode();
    }

    protected static ArrayNode array() {
        return JSON.createArrayNode();
    }
}
