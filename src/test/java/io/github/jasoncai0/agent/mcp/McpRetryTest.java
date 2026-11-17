package io.github.jasoncai0.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRetryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void retriesTransientHttpFailuresDuringConnect() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, initializeCalls, 2, 500));
        server.start();
        try {
            McpManager manager = connect(server);
            Map<String, Object> status = manager.status().get(0);
            assertEquals(true, status.get("connected"));
            assertEquals(3, status.get("attempts"));
            assertTrue(initializeCalls.get() >= 3, "initialize calls: " + initializeCalls.get());
            manager.close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryAuthenticationFailures() throws Exception {
        AtomicInteger initializeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> handle(exchange, initializeCalls, 99, 401));
        server.start();
        try {
            McpManager manager = connect(server);
            Map<String, Object> status = manager.status().get(0);
            assertEquals(false, status.get("connected"));
            assertEquals(1, status.get("attempts"));
            assertEquals(1, initializeCalls.get());
            manager.close();
        } finally {
            server.stop(0);
        }
    }

    private static McpManager connect(HttpServer server) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        McpServerConfig config = new McpServerConfig(
                "retry", "http", "", List.of(), Map.of(), url, Map.of(), 10, true);
        ToolRegistry registry = BuiltInTools.registry();
        return McpManager.connect(new McpConfig(List.of(config)), registry,
                Path.of(".").toAbsolutePath());
    }

    private static void handle(HttpExchange exchange, AtomicInteger initializeCalls, int failures,
                               int failureStatus) throws IOException {
        try (exchange) {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            int call = initializeCalls.incrementAndGet();
            if (method.equals("initialize") && call <= failures) {
                byte[] body = "temporary failure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failureStatus, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (method.equals("notifications/initialized")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ObjectNode result = JSON.createObjectNode();
            switch (method) {
                case "initialize" -> {
                    result.put("protocolVersion", "2025-06-18");
                    result.set("capabilities", JSON.createObjectNode());
                    result.set("serverInfo", JSON.createObjectNode()
                            .put("name", "retry-server").put("version", "1.0"));
                }
                case "tools/list" -> result.set("tools", JSON.createArrayNode());
                default -> {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }
            ObjectNode response = JSON.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.get("id"));
            response.set("result", result);
            byte[] body = JSON.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
