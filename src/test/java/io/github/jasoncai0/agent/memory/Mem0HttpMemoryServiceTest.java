package io.github.jasoncai0.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mem0HttpMemoryServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private MemorySettings settings;
    private final AtomicReference<JsonNode> addBody = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<JsonNode> searchBody = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicInteger getCount = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        settings = new MemorySettings(MemoryMode.ON, "mem0-oss",
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret", "u1", 5, 500, 2000, true);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void healthRecallAndWriteRoundTrip() {
        Mem0HttpMemoryService memory = new Mem0HttpMemoryService(settings);
        MemoryScope scope = new MemoryScope("u1", "jade", "project-a", "demo", "run-1");
        assertTrue(memory.health().available());
        assertTrue(memory.health().available());
        assertEquals(1, getCount.get(), "health() should use short TTL cache instead of hitting GET every call");

        List<MemoryItem> items = memory.recall("what indentation", scope, 5);
        assertEquals(1, items.size());
        assertEquals("Use 4 spaces.", items.get(0).text());
        assertEquals("project-a", items.get(0).projectId());
        assertTrue(searchBody.get().path("query").asText().contains("indentation"));

        String id = memory.remember(new MemoryCandidate("Use 4 spaces.", "preference",
                "user", 0.9, true, false, "run-1"), scope);
        assertEquals("mem-new", id);
        assertTrue(addBody.get().path("infer").asBoolean());
        assertEquals("project-a", addBody.get().path("metadata").path("project_id").asText());
        assertEquals("secret", apiKey.get());
    }

    @Test
    void returnsNoopWhenEndpointUnavailable() {
        MemorySettings dead = new MemorySettings(MemoryMode.ON, "mem0-oss",
                "http://127.0.0.1:1", "", "u1", 5, 100, 200, true);
        assertFalse(new Mem0HttpMemoryService(dead).health().available());
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("GET")) {
            getCount.incrementAndGet();
            respond(exchange, "{\"results\":[{\"id\":\"mem-1\",\"memory\":\"Use 4 spaces.\","
                    + "\"score\":0.9,\"metadata\":{\"kind\":\"preference\",\"scope\":\"project\","
                    + "\"project_id\":\"project-a\"}}]}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node = JSON.readTree(body);
        if (exchange.getRequestURI().getPath().equals("/search")) {
            searchBody.set(node);
            respond(exchange, "{\"results\":[{\"id\":\"mem-1\",\"memory\":\"Use 4 spaces.\","
                    + "\"score\":0.9,\"metadata\":{\"kind\":\"preference\",\"scope\":\"project\","
                    + "\"project_id\":\"project-a\"}}]}");
        } else {
            addBody.set(node);
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            respond(exchange, "{\"results\":[{\"id\":\"mem-new\"}]}");
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
