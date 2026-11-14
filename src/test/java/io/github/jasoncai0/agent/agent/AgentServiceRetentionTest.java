package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceRetentionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void restoresFinishedRunsAndTheirEventLogAfterRestart() throws Exception {
        AppConfig config = config();
        ScriptedLlm firstLlm = new ScriptedLlm();
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("survives-restart");
                    }
                }
                """;
        firstLlm.script.add(new ChatResult(null, null,
                List.of(new ToolCall("write-1", "write_file",
                        JSON.writeValueAsString(Map.of("path", "src/Main.java", "content", source)))),
                "tool_calls", new Usage(10, 5, 15)));
        firstLlm.script.add(new ChatResult("Saved session.", null, List.of(), "stop",
                new Usage(12, 4, 16)));

        RunSnapshot finished;
        try (AgentService service = new AgentService(config, firstLlm)) {
            service.start("Create a retained session", "retained-session");
            finished = service.await(service.list().get(0).id(), Duration.ofSeconds(20));
        }
        assertEquals("SUCCEEDED", finished.status());
        assertTrue(Files.isRegularFile(config.workspacesDir()
                .resolve("retained-session/.agent/transcript.json")));
        assertTrue(Files.readString(config.workspacesDir()
                        .resolve("retained-session/.agent/events.jsonl"))
                .contains("\"run.finished\""));

        try (AgentService restarted = new AgentService(config, new ScriptedLlm())) {
            List<RunSnapshot> sessions = restarted.list();
            assertEquals(1, sessions.size());
            RunSnapshot restored = sessions.get(0);
            assertEquals(finished.id(), restored.id());
            assertEquals("SUCCEEDED", restored.status());
            assertEquals("Saved session.", restored.finalAnswer());
            assertTrue(restored.transcriptPath().endsWith("transcript.json"));

            AgentRun run = restarted.require(finished.id());
            assertTrue(run.events().closed());
            assertTrue(run.events().snapshot().stream()
                    .anyMatch(event -> "run.finished".equals(event.type())));
            assertTrue(restarted.readFile(finished.id(), "src/Main.java")
                    .contains("survives-restart"));
        }
    }

    @Test
    void marksAnInterruptedRunAsFailedOnRestore() throws Exception {
        AppConfig config = config();
        Path dir = config.workspacesDir().resolve("interrupted");
        Files.createDirectories(dir.resolve(".agent"));
        Files.writeString(dir.resolve(".agent/transcript.json"), """
                {
                  "id": "run-interrupted",
                  "task": "Was running when the process died",
                  "workspace": "interrupted",
                  "status": "RUNNING",
                  "startedAt": 1,
                  "finishedAt": 0,
                  "plan": [],
                  "files": []
                }
                """);
        try (AgentService restarted = new AgentService(config, new ScriptedLlm())) {
            RunSnapshot restored = restarted.require("run-interrupted").snapshot();
            assertEquals("FAILED", restored.status());
            assertEquals("Run was interrupted by a server restart", restored.error());
            assertTrue(restored.terminal());
        }
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
                new AgentSettings(3, 3, 20_000, 30, true),
                new ServerSettings("127.0.0.1", 0)
        );
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
