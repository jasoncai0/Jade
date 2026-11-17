package io.github.jasoncai0.agent.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.config.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the SSE path end to end against a local mock server. */
class DeepSeekClientStreamTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsTextReasoningAndUsage() throws Exception {
        startSse("""
                data: {"choices":[{"delta":{"reasoning_content":"think "},"index":0}]}

                data: {"choices":[{"delta":{"reasoning_content":"first"},"index":0}]}

                data: {"choices":[{"delta":{"content":"Hello"}}]}

                data: {"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}

                data: [DONE]

                """);

        Recorder recorder = new Recorder();
        ChatResult result = client(false).stream(prompt(), recorder);

        assertEquals("Hello world", result.content());
        assertEquals("think first", result.reasoningContent());
        assertEquals("stop", result.finishReason());
        assertEquals(17, result.usage().totalTokens());
        assertEquals(12, result.usage().promptTokens());
        assertEquals(List.of("text:Hello", "text: world"), recorder.texts);
        assertEquals(List.of("reasoning:think ", "reasoning:first"), recorder.reasonings);
        assertEquals(1, recorder.usages.size());
        assertEquals(17, recorder.usages.get(0).totalTokens());
    }

    @Test
    void assemblesToolCallFragmentsByIndex() throws Exception {
        startSse("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"read_file","arguments":"{\\"path\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.txt\\"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"list_files","arguments":"{}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """);

        Recorder recorder = new Recorder();
        ChatResult result = client(false).stream(prompt(), recorder);

        assertEquals(2, result.toolCalls().size());
        assertEquals("read_file", result.toolCalls().get(0).name());
        assertEquals("{\"path\":\"a.txt\"}", result.toolCalls().get(0).arguments());
        assertEquals("call_a", result.toolCalls().get(0).id());
        assertEquals("list_files", result.toolCalls().get(1).name());
        assertEquals("tool_calls", result.finishReason());
        assertEquals(List.of("tool:0:read_file:{\"path\":", "tool:0:null:\"a.txt\"}", "tool:1:list_files:{}"),
                recorder.toolDeltas);
    }

    @Test
    void nonTwoHundredCarriesErrorCode() throws Exception {
        start(exchange -> {
            readBody(exchange);
            respond(exchange, 400, """
                    {"error":{"message":"This model's maximum context length is 65536 tokens",
                    "code":"context_length_exceeded"}}
                    """);
        });

        LlmException error = assertThrows(LlmException.class, () -> client(false).stream(prompt(), new Recorder()));

        assertEquals(400, error.statusCode());
        assertEquals("context_length_exceeded", error.code());
        assertTrue(error.isContextOverflow());
    }

    @Test
    void truncatedStreamFailsInsteadOfReturningHalfAnAnswer() throws Exception {
        startSse("""
                data: {"choices":[{"delta":{"content":"partial"}}]}

                """);

        LlmException error = assertThrows(LlmException.class, () -> client(false).stream(prompt(), new Recorder()));

        assertEquals("STREAM_TRUNCATED", error.code());
        assertFalse(error.isContextOverflow());
    }

    @Test
    void finishReasonWithoutDoneIsAccepted() throws Exception {
        startSse("""
                data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}

                """);

        ChatResult result = client(false).stream(prompt(), new Recorder());

        assertEquals("hi", result.content());
    }

    @Test
    void streamBodyAsksForStreaming() {
        ChatRequest request = prompt();
        DeepSeekClient local = clientAt("http://127.0.0.1:1", false);
        String body = local.requestBody(request, true);

        assertTrue(body.contains("\"stream\":true"), body);
        assertFalse(body.contains("stream_options"), body);
        assertFalse(local.requestBody(request).contains("\"stream\":true"));
    }

    @Test
    void rejectedStreamOptionsIsRetriedWithoutIt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> firstBody = new AtomicReference<>();
        AtomicReference<String> secondBody = new AtomicReference<>();
        start(exchange -> {
            String body = readBody(exchange);
            int attempt = requests.incrementAndGet();
            if (attempt == 1) {
                firstBody.set(body);
                respond(exchange, 400, "{\"error\":{\"message\":\"Unknown parameter: stream_options.include_usage\"}}");
            } else {
                secondBody.set(body);
                writeSse(exchange, """
                        data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """);
            }
        });

        ChatResult result = client(true).stream(prompt(), new Recorder());

        assertEquals("ok", result.content());
        assertEquals(2, requests.get());
        assertTrue(firstBody.get().contains("stream_options"), firstBody.get());
        assertFalse(secondBody.get().contains("stream_options"), secondBody.get());
        assertTrue(secondBody.get().contains("\"stream\":true"), secondBody.get());
    }

    private ChatRequest prompt() {
        return new ChatRequest(List.of(ChatMessage.user("hi")), List.of(), 0.2, 32);
    }

    private DeepSeekClient client(boolean includeUsageByDefault) {
        return clientAt("http://127.0.0.1:" + server.getAddress().getPort(), includeUsageByDefault);
    }

    private DeepSeekClient clientAt(String baseUrl, boolean includeUsageByDefault) {
        return new DeepSeekClient(new LlmSettings(
                "sk-test", baseUrl, "deepseek-chat", 10, 4096),
                Duration.ofMillis(1), includeUsageByDefault);
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private void startSse(String chunks) throws Exception {
        start(exchange -> {
            readBody(exchange);
            writeSse(exchange, chunks);
        });
    }

    private void writeSse(com.sun.net.httpserver.HttpExchange exchange, String chunks) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(chunks.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private String readBody(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class Recorder implements LlmClient.StreamListener {
        private final List<String> texts = new ArrayList<>();
        private final List<String> reasonings = new ArrayList<>();
        private final List<String> toolDeltas = new ArrayList<>();
        private final List<Usage> usages = new ArrayList<>();

        @Override
        public void onText(String delta) {
            texts.add("text:" + delta);
        }

        @Override
        public void onReasoning(String delta) {
            reasonings.add("reasoning:" + delta);
        }

        @Override
        public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
            toolDeltas.add("tool:" + index + ":" + name + ":" + argumentsDelta);
        }

        @Override
        public void onUsage(Usage usage) {
            usages.add(usage);
        }
    }
}
