package io.github.jasoncai0.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.agent.RunSnapshot;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.LlmSettings;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSE must carry live deltas and still end with the durable transcript. */
class WebServerStreamTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path temp;

    @Test
    void deltasArriveBeforeTheDurableAssistantAndCarryNoEventId() throws Exception {
        AppConfig config = new AppConfig(
                temp,
                temp.resolve("skills"),
                temp.resolve("runs"),
                new LlmSettings("sk-test", "http://127.0.0.1:1", "deepseek-chat", 5, 1024),
                new AgentSettings(3, 3, 20_000, 30, true),
                new ServerSettings("127.0.0.1", 0));
        Files.createDirectories(config.skillsDir());
        SlowStreamingLlm llm = new SlowStreamingLlm();
        AgentService service = new AgentService(config, llm);
        WebServer server = new WebServer(service, config.server());
        try {
            server.start();
            String base = server.url();
            HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(base + "/api/runs"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"task\":\"stream please\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, created.statusCode());
            String runId = JSON.readTree(created.body()).path("data").path("run").path("id").asText();

            String raw = readUntilFinalAssistant(base + "/api/runs/" + runId + "/events");

            List<String> frames = List.of(raw.split("\n\n"));
            long deltaFrames = frames.stream()
                    .filter(frame -> frame.lines().anyMatch(line -> line.equals("event: assistant.delta")))
                    .count();
            assertTrue(deltaFrames >= 2, "expected progressive frames, got " + deltaFrames + " in:\n" + raw);
            long finalAssistant = frames.stream()
                    .filter(frame -> frame.lines().anyMatch(line -> line.equals("event: assistant")))
                    .count();
            assertEquals(1, finalAssistant);
            for (String frame : frames) {
                if (frame.lines().anyMatch(line -> line.equals("event: assistant.delta"))) {
                    assertFalse(frame.lines().anyMatch(line -> line.startsWith("id:")),
                            "delta frames must not be replayable: " + frame);
                    assertTrue(frame.contains("\"text\""), frame);
                }
            }
            RunSnapshot snapshot = service.await(runId, Duration.ofSeconds(10));
            assertEquals("SUCCEEDED", snapshot.status());
        } finally {
            server.close();
            service.close();
        }
    }

    private String readUntilFinalAssistant(String url) throws Exception {
        HttpResponse<java.io.InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        StringBuilder raw = new StringBuilder();
        StringBuilder frame = new StringBuilder();
        boolean completedFinalAssistant = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                if (line.isEmpty()) {
                    if (frame.toString().lines()
                            .anyMatch(entry -> entry.equals("event: assistant"))) {
                        completedFinalAssistant = true;
                        break;
                    }
                    frame.setLength(0);
                } else {
                    frame.append(line).append('\n');
                }
            }
            assertTrue(completedFinalAssistant, "never saw the durable assistant event:\n" + raw);
        } catch (java.io.IOException ignored) {
            // connection closed by the server
        }
        return raw.toString();
    }

    /** Emits three chunks 120 ms apart so the SSE connection sees them live. */
    private static final class SlowStreamingLlm implements LlmClient {
        @Override
        public ChatResult chat(ChatRequest request) {
            throw new UnsupportedOperationException("streaming path only");
        }

        @Override
        public ChatResult stream(ChatRequest request, StreamListener listener) {
            String[] chunks = {"one ", "two ", "three"};
            for (String chunk : chunks) {
                listener.onText(chunk);
                sleep(120);
            }
            return new ChatResult("one two three", null, List.of(), "stop", new Usage(5, 3, 8));
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
