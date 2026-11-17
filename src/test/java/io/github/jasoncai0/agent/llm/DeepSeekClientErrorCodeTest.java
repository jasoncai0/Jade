package io.github.jasoncai0.agent.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.config.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientErrorCodeTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesContextLengthCodeFromJsonBody() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 400, """
                    {"error":{"message":"This model's maximum context length is 65536 tokens",
                    "code":"context_length_exceeded"}}
                    """);
        });

        LlmException error = chatAndCatch();

        assertEquals(400, error.statusCode());
        assertEquals("context_length_exceeded", error.code());
        assertTrue(error.isContextOverflow());
        assertEquals(1, requests.get());
    }

    @Test
    void detectsOverflowFromPlainTextMessage() throws Exception {
        start(exchange -> respond(exchange, 400, "maximum context window exceeded"));

        LlmException error = chatAndCatch();

        assertNull(error.code());
        assertTrue(error.isContextOverflow());
    }

    @Test
    void fallsBackToErrorTypeWhenCodeIsMissing() throws Exception {
        start(exchange -> respond(exchange, 400,
                "{\"error\":{\"message\":\"too many tokens\",\"type\":\"CONTEXT_WINDOW_EXCEEDED\"}}"));

        LlmException error = chatAndCatch();

        assertEquals("CONTEXT_WINDOW_EXCEEDED", error.code());
        assertTrue(error.isContextOverflow());
    }

    @Test
    void ordinaryFourHundredIsNotTreatedAsOverflow() throws Exception {
        start(exchange -> respond(exchange, 400,
                "{\"error\":{\"message\":\"invalid request\",\"code\":\"invalid_request_error\"}}"));

        LlmException error = chatAndCatch();

        assertEquals("invalid_request_error", error.code());
        assertFalse(error.isContextOverflow());
    }

    @Test
    void rateLimitStillUsesTheExistingRetryPath() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        start(exchange -> {
            int attempt = requests.incrementAndGet();
            if (attempt == 1) {
                respond(exchange, 429, "{\"error\":{\"message\":\"slow down\"}}");
            } else {
                respond(exchange, 200, """
                        {"choices":[{"message":{"role":"assistant","content":"ok"},
                        "finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,
                        "total_tokens":2}}
                        """);
            }
        });

        ChatResult result = client().chat(new ChatRequest(
                List.of(ChatMessage.user("hi")), List.of(), 0.2, 32));

        assertEquals("ok", result.content());
        assertEquals(2, requests.get());
    }

    private LlmException chatAndCatch() {
        return assertThrows(LlmException.class, () -> client().chat(new ChatRequest(
                List.of(ChatMessage.user("hi")), List.of(), 0.2, 32)));
    }

    private DeepSeekClient client() {
        return new DeepSeekClient(new LlmSettings(
                "sk-test", "http://127.0.0.1:" + server.getAddress().getPort(),
                "deepseek-chat", 10, 1024), Duration.ofMillis(1));
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
