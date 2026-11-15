package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.LlmSettings;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceFollowUpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void restoresASessionAndContinuesItWithTheSameWorkspaceAndHistory() throws Exception {
        AppConfig config = config();
        String first = source("v1");
        String second = source("v2");

        ScriptedLlm firstLlm = new ScriptedLlm(
                toolResult("write-1", "write_file", Map.of("path", "src/Main.java", "content", first)),
                toolResult("run-1", "run_java", Map.of("mainClass", "Main")),
                finalResult("version one complete"));
        RunSnapshot one;
        try (AgentService service = new AgentService(config, firstLlm)) {
            service.start("Create a Java CLI that prints v1", "follow-up-session");
            String id = service.list().get(0).id();
            one = service.await(id, Duration.ofSeconds(20));
        }
        assertEquals("SUCCEEDED", one.status());
        assertEquals(3, one.step());
        assertTrue(Files.readString(config.workspacesDir().resolve("follow-up-session/src/Main.java"))
                .contains("v1"));

        ScriptedLlm secondLlm = new ScriptedLlm(
                toolResult("write-2", "write_file", Map.of("path", "src/Main.java", "content", second)),
                toolResult("run-2", "run_java", Map.of("mainClass", "Main")),
                finalResult("version two complete"));

        try (AgentService restarted = new AgentService(config, secondLlm)) {
            AgentRun run = restarted.require(one.id());
            assertTrue(run.events().closed());
            assertFalse(run.running());

            RunSnapshot queued = restarted.followUp(one.id(), "Change the output to v2");
            assertEquals("RUNNING", queued.status());
            assertEquals(0, queued.step());

            RunSnapshot two = restarted.await(one.id(), Duration.ofSeconds(20));
            assertEquals("SUCCEEDED", two.status());
            assertEquals(3, two.step());
            assertEquals("version two complete", two.finalAnswer());
            assertTrue(two.usage().totalTokens() > one.usage().totalTokens());
            assertTrue(Files.readString(config.workspacesDir().resolve("follow-up-session/src/Main.java"))
                    .contains("v2"));
            assertFalse(run.events().closed());

            List<AgentEvent> events = run.events().snapshot();
            assertEquals(2, events.stream().filter(e -> e.type().equals("run.finished")).count());
            assertEquals(1, events.stream().filter(e -> e.type().equals("turn.started")).count());
            long maxSeq = events.stream().mapToLong(AgentEvent::seq).max().orElse(0);
            assertEquals(events.size(), maxSeq);

            List<ChatMessage> history = RunArtifacts.loadMessages(run.workspace().root());
            assertTrue(history.stream().anyMatch(m -> m.role().name().equals("USER")
                    && m.content() != null && m.content().contains("Change the output to v2")));
            assertTrue(secondLlm.requests.get(0).messages().stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("Create a Java CLI")));
        }
        assertTrue(Files.isRegularFile(config.workspacesDir()
                .resolve("follow-up-session/.agent/transcript.json")));
    }

    private AppConfig config() throws Exception {
        Path root = temp.resolve("app");
        Path skills = root.resolve("skills");
        Files.createDirectories(skills);
        return new AppConfig(
                root,
                skills,
                root.resolve("runs"),
                new LlmSettings("sk-test", "http://127.0.0.1:1", "deepseek-chat", 5, 1024),
                new AgentSettings(4, 4, 20_000, 30, true),
                new ServerSettings("127.0.0.1", 0)
        );
    }

    private static String source(String value) {
        return """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("%s");
                    }
                }
                """.formatted(value);
    }

    private static ChatResult toolResult(String id, String name, Map<String, Object> args) throws Exception {
        return new ChatResult(null, null,
                List.of(new ToolCall(id, name, JSON.writeValueAsString(args))),
                "tool_calls", new Usage(10, 5, 15));
    }

    private static ChatResult finalResult(String content) {
        return new ChatResult(content, null, List.of(), "stop", new Usage(20, 8, 28));
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> script = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        @SafeVarargs
        private ScriptedLlm(ChatResult... results) {
            script.addAll(List.of(results));
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            ChatResult next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response left");
            }
            return next;
        }
    }
}
