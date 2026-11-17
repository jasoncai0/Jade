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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebServerForkTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path temp;

    @Test
    void forksAFinishedRunAndAcceptsAFollowUpOverHttp() throws Exception {
        Path root = temp.resolve("app");
        Path skills = root.resolve("skills");
        Files.createDirectories(skills);
        AppConfig config = new AppConfig(
                root,
                skills,
                root.resolve("runs"),
                new LlmSettings("sk-test", "http://127.0.0.1:1", "deepseek-chat", 5, 1024),
                new AgentSettings(4, 4, 20_000, 30, true),
                new ServerSettings("127.0.0.1", 0)
        );
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(new ChatResult(null, null,
                List.of(new ToolCall("call-1", "write_file",
                        JSON.writeValueAsString(Map.of("path", "notes.txt", "content", "parent")))),
                "tool_calls", new Usage(10, 5, 15)));
        llm.script.add(new ChatResult("Parent done.", null, List.of(), "stop", new Usage(12, 4, 16)));
        llm.script.add(new ChatResult("Child done.", null, List.of(), "stop", new Usage(12, 4, 16)));

        AgentService service = new AgentService(config, llm);
        WebServer server = new WebServer(service, config.server());
        try {
            server.start();
            String base = server.url();

            HttpResponse<String> created = post(base + "/api/runs", "{\"task\":\"Write notes\"}");
            assertEquals(202, created.statusCode(), created.body());
            String parentId = JSON.readTree(created.body()).path("data").path("run").path("id").asText();
            assertEquals("SUCCEEDED", service.await(parentId, Duration.ofSeconds(20)).status());

            HttpResponse<String> forked = post(base + "/api/runs/" + parentId + "/fork",
                    "{\"name\":\"web-child\"}");
            assertEquals(201, forked.statusCode(), forked.body());
            JsonNode data = JSON.readTree(forked.body()).path("data");
            String forkId = data.path("run").path("id").asText();
            assertTrue(forkId.startsWith("fork-"));
            assertEquals(parentId, data.path("run").path("parentRunId").asText());
            assertEquals(parentId, data.path("parentRunId").asText());
            assertEquals("/api/runs/" + forkId + "/events", data.path("eventsUrl").asText());
            assertTrue(data.path("run").path("workspace").asText().startsWith("web-child-fork-"));

            HttpResponse<String> fetched = get(base + "/api/runs/" + forkId);
            assertEquals(200, fetched.statusCode());
            assertEquals(parentId, JSON.readTree(fetched.body()).path("data").path("parentRunId").asText());

            HttpResponse<String> followUp = post(base + "/api/runs/" + forkId + "/messages",
                    "{\"message\":\"Continue from here\"}");
            assertEquals(202, followUp.statusCode(), followUp.body());
            RunSnapshot finished = service.await(forkId, Duration.ofSeconds(20));
            assertEquals("SUCCEEDED", finished.status());
            assertEquals("Child done.", finished.finalAnswer());
            assertEquals(parentId, finished.parentRunId());
        } finally {
            server.close();
            service.close();
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String url, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> script = new ArrayDeque<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            return script.isEmpty()
                    ? new ChatResult("done", null, List.of(), "stop", new Usage(1, 1, 2))
                    : script.removeFirst();
        }
    }
}
