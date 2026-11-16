package io.github.jasoncai0.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void discoversAndCallsStreamableHttpTool() throws Exception {
        AtomicReference<String> seenSession = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, seenSession, seenAuth));
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            McpServerConfig config = new McpServerConfig(
                    "test", "http", "", List.of(), Map.of(), url,
                    Map.of("Authorization", "Bearer test-token"), 10, true);
            ToolRegistry registry = BuiltInTools.registry();
            McpManager manager = McpManager.connect(new McpConfig(List.of(config)), registry,
                    Path.of(".").toAbsolutePath());

            assertEquals(1, manager.toolCount());
            var tool = registry.find("mcp_test_echo");
            assertTrue(tool.isPresent());
            ToolResult result = tool.get().execute(null, Map.of("text", "hello"));
            assertTrue(result.ok(), result.output());
            assertEquals("echo:hello", result.output());
            assertEquals("s1", seenSession.get());
            assertEquals("Bearer test-token", seenAuth.get());
            manager.close();
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> seenSession,
                               AtomicReference<String> seenAuth) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = JSON.readTree(body);
            String method = request.path("method").asText();
            seenSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));

            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ObjectNode result = JSON.createObjectNode();
            if (method.equals("initialize")) {
                result.put("protocolVersion", "2025-06-18");
                result.set("capabilities", JSON.createObjectNode());
                result.set("serverInfo", JSON.createObjectNode()
                        .put("name", "fake").put("version", "1.0"));
                exchange.getResponseHeaders().add("Mcp-Session-Id", "s1");
            } else if (method.equals("tools/list")) {
                var tool = JSON.createObjectNode();
                tool.put("name", "echo");
                tool.put("description", "Echo text");
                var schema = JSON.createObjectNode();
                schema.put("type", "object");
                schema.set("properties", JSON.createObjectNode()
                        .set("text", JSON.createObjectNode().put("type", "string")));
                tool.set("inputSchema", schema);
                tool.set("annotations", JSON.createObjectNode().put("readOnlyHint", true));
                result.set("tools", JSON.createArrayNode().add(tool));
            } else if (method.equals("tools/call")) {
                String text = request.path("params").path("arguments").path("text").asText("?");
                var content = JSON.createObjectNode();
                content.put("type", "text");
                content.put("text", "echo:" + text);
                result.set("content", JSON.createArrayNode().add(content));
                result.put("isError", false);
            } else {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            byte[] bytes = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Test
    void expandsProjectRootInConfig() throws Exception {
        Path root = java.nio.file.Files.createTempDirectory("jade-mcp-config");
        java.nio.file.Files.writeString(root.resolve("mcp.json"), """
                {"mcpServers":{"local":{"type":"stdio","command":"${PROJECT_ROOT}/bin/server","args":["--x"]}}}
                """);
        McpConfig config = McpConfig.load(root);
        assertEquals(1, config.servers().size());
        assertEquals(root.resolve("bin/server").toString(), config.servers().get(0).command());
        assertNotNull(config.servers().get(0).args());
    }
}
