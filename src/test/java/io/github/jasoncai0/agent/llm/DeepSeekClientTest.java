package io.github.jasoncai0.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.config.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsToolsAndParsesToolCalls() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":null,
                    "tool_calls":[{"id":"call_1","type":"function","function":{
                    "name":"write_file","arguments":"{\\"path\\":\\"src/Main.java\\"}"}}]},
                    "finish_reason":"tool_calls"}],
                    "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DeepSeekClient client = new DeepSeekClient(settings(server.getAddress().getPort()),
                Duration.ofMillis(1));
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system("You are a Java agent."), ChatMessage.user("write Main")),
                List.of(new ToolSpec("write_file", "Write a file.",
                        Map.of("type", "object", "properties",
                                Map.of("path", Map.of("type", "string")),
                                "required", List.of("path"),
                                "additionalProperties", false), true)),
                0.2, 512, ResponseFormat.JSON_OBJECT);
        String traced = client.requestBody(request);
        assertTrue(traced != null && traced.contains("\"model\":\"deepseek-chat\""), traced);
        ChatResult result = client.chat(request);

        assertNull(result.content());
        assertEquals(1, result.toolCalls().size());
        assertEquals("write_file", result.toolCalls().get(0).name());
        assertEquals("{\"path\":\"src/Main.java\"}", result.toolCalls().get(0).arguments());
        assertEquals(15, result.usage().totalTokens());
        assertEquals("Bearer sk-test", authorization.get());

        JsonNode sent = JSON.readTree(requestBody.get());
        assertEquals(sent, JSON.readTree(traced));
        assertEquals("deepseek-chat", sent.path("model").asText());
        assertEquals(false, sent.path("stream").asBoolean());
        assertEquals("auto", sent.path("tool_choice").asText());
        assertEquals("json_object", sent.path("response_format").path("type").asText());
        assertEquals("write_file", sent.path("tools").get(0).path("function").path("name").asText());
        assertEquals(true, sent.path("tools").get(0).path("function").path("strict").asBoolean());
        assertEquals(2, sent.path("messages").size());
        assertEquals("system", sent.path("messages").get(0).path("role").asText());
    }

    @Test
    void omitsOptionalResponseFormatAndStrictByDefault() throws Exception {
        DeepSeekClient client = new DeepSeekClient(settings(1), Duration.ofMillis(1));
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.user("hello")),
                List.of(new ToolSpec("echo", "Echo a value.",
                        Map.of("type", "object", "properties",
                                Map.of("value", Map.of("type", "string"))))),
                0.2, 64);

        JsonNode sent = JSON.readTree(client.requestBody(request));

        assertFalse(sent.has("response_format"), sent.toString());
        assertFalse(sent.path("tools").get(0).path("function").has("strict"), sent.toString());
    }

    @Test
    void surfacesApiErrorsWithoutRetrying4xx() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] response = "{\"error\":{\"message\":\"invalid api key\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        DeepSeekClient client = new DeepSeekClient(settings(server.getAddress().getPort()),
                Duration.ofMillis(1));
        LlmException error = assertThrows(LlmException.class, () -> client.chat(new ChatRequest(
                List.of(ChatMessage.user("hi")), List.of(), 0.2, 32)));

        assertTrue(error.getMessage().contains("invalid api key"));
        assertEquals(401, error.statusCode());
        assertEquals(1, requests.get());
    }

    private static LlmSettings settings(int port) {
        return new LlmSettings("sk-test", "http://127.0.0.1:" + port, "deepseek-chat", 10, 1024);
    }
}
