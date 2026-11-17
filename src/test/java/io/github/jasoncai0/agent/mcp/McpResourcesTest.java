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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResourcesTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exposesResourceToolsOnlyWhenServerAdvertisesThem() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", McpResourcesTest::handle);
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            McpServerConfig config = new McpServerConfig(
                    "docs", "http", "", List.of(), Map.of(), url,
                    Map.of(), 10, true);
            ToolRegistry registry = BuiltInTools.registry();
            McpManager manager = McpManager.connect(new McpConfig(List.of(config)), registry,
                    Path.of(".").toAbsolutePath());

            assertEquals(3, manager.toolCount());
            var list = registry.find("mcp_docs_list_resources");
            var templates = registry.find("mcp_docs_list_resource_templates");
            var read = registry.find("mcp_docs_read_resource");
            assertTrue(list.isPresent());
            assertTrue(templates.isPresent());
            assertTrue(read.isPresent());

            ToolResult listed = list.get().execute(null, Map.of());
            assertTrue(listed.ok(), listed.output());
            assertTrue(listed.output().contains("file:///guide/intro.md"), listed.output());

            ToolResult templateList = templates.get().execute(null, Map.of());
            assertTrue(templateList.ok(), templateList.output());
            assertTrue(templateList.output().contains("file:///{path}"), templateList.output());

            ToolResult content = read.get().execute(null, Map.of("uri", "file:///guide/intro.md"));
            assertTrue(content.ok(), content.output());
            assertEquals("hello resource", content.output());
            assertTrue(content.data().containsKey("contents"));

            ToolResult missing = read.get().execute(null, Map.of());
            assertTrue(!missing.ok());
            assertTrue(missing.output().contains("uri"), missing.output());
            manager.close();
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ObjectNode result = JSON.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18");
                    ObjectNode capabilities = JSON.createObjectNode();
                    capabilities.set("resources", JSON.createObjectNode());
                    result.set("capabilities", capabilities);
                    result.set("serverInfo", JSON.createObjectNode()
                            .put("name", "fake-docs").put("version", "1.0"));
                }
                case "tools/list" -> result.set("tools", JSON.createArrayNode());
                case "resources/list" -> {
                    ObjectNode resource = JSON.createObjectNode();
                    resource.put("uri", "file:///guide/intro.md");
                    resource.put("name", "intro");
                    resource.put("description", "Getting started");
                    resource.put("mimeType", "text/markdown");
                    result.set("resources", JSON.createArrayNode().add(resource));
                }
                case "resources/templates/list" -> {
                    ObjectNode template = JSON.createObjectNode();
                    template.put("uriTemplate", "file:///{path}");
                    template.put("name", "file");
                    template.put("mimeType", "text/markdown");
                    result.set("resourceTemplates", JSON.createArrayNode().add(template));
                }
                case "resources/read" -> {
                    String uri = request.path("params").path("uri").asText();
                    ObjectNode content = JSON.createObjectNode();
                    content.put("uri", uri);
                    content.put("mimeType", "text/markdown");
                    content.put("text", "hello resource");
                    result.set("contents", JSON.createArrayNode().add(content));
                }
                default -> {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
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
}
