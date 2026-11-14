package io.github.jasoncai0.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path temp;

    @Test
    void servesTheConsoleApiAndARealRun() throws Exception {
        Path root = temp.resolve("app");
        Path skills = root.resolve("skills");
        Files.createDirectories(skills);
        AppConfig config = new AppConfig(
                root,
                skills,
                root.resolve("runs"),
                new LlmSettings("sk-test", "http://127.0.0.1:1", "deepseek-chat", 5, 1024),
                new AgentSettings(3, 3, 20_000, 30, true),
                new ServerSettings("127.0.0.1", 0)
        );
        ScriptedLlm llm = new ScriptedLlm();
        String source = """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("built-by-web");
                    }
                }
                """;
        llm.script.add(new ChatResult(null, null,
                List.of(new ToolCall("call-1", "write_file",
                        JSON.writeValueAsString(Map.of("path", "src/Hello.java", "content", source)))),
                "tool_calls", new Usage(10, 5, 15)));
        llm.script.add(new ChatResult("Built Hello.", null, List.of(), "stop", new Usage(12, 4, 16)));

        AgentService service = new AgentService(config, llm);
        WebServer server = new WebServer(service, config.server());
        try {
            server.start();
            String base = server.url();

            HttpResponse<String> health = get(base + "/api/health");
            assertEquals(200, health.statusCode());
            JsonNode healthJson = JSON.readTree(health.body());
            assertEquals(0, healthJson.path("code").asInt());
            assertEquals("ok", healthJson.path("data").path("status").asText());
            assertTrue(healthJson.path("data").path("apiKeyConfigured").asBoolean());

            HttpResponse<String> page = get(base + "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("JADE"));
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));

            HttpResponse<String> created = post(base + "/api/runs", "{\"task\":\"Build a Hello CLI\"}");
            assertEquals(202, created.statusCode());
            JsonNode createdJson = JSON.readTree(created.body());
            String runId = createdJson.path("data").path("run").path("id").asText();
            assertTrue(runId.startsWith("run-"));
            assertEquals("/api/runs/" + runId + "/events",
                    createdJson.path("data").path("eventsUrl").asText());

            RunSnapshot snapshot = service.await(runId, Duration.ofSeconds(20));
            assertEquals("SUCCEEDED", snapshot.status());

            HttpResponse<String> run = get(base + "/api/runs/" + runId);
            assertEquals("SUCCEEDED", JSON.readTree(run.body()).path("data").path("status").asText());

            HttpResponse<String> files = get(base + "/api/runs/" + runId + "/files");
            assertTrue(files.body().contains("src/Hello.java"));

            String encoded = URLEncoder.encode("src/Hello.java", StandardCharsets.UTF_8);
            HttpResponse<String> file = get(base + "/api/runs/" + runId + "/file?path=" + encoded);
            assertTrue(JSON.readTree(file.body()).path("data").path("content").asText()
                    .contains("built-by-web"));

            HttpResponse<String> events = get(base + "/api/runs/" + runId + "/events");
            assertTrue(events.body().contains("event: phase"), events.body());
            assertTrue(events.body().contains("event: run.finished"));
            assertTrue(events.body().contains("event: stream_end"));

            HttpResponse<String> invalid = post(base + "/api/runs", "{}");
            assertEquals(400, invalid.statusCode());
            assertEquals(400, JSON.readTree(invalid.body()).path("code").asInt());
        } finally {
            server.close();
            service.close();
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> script = new ArrayDeque<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            ChatResult next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response left");
            }
            return next;
        }
    }
}
