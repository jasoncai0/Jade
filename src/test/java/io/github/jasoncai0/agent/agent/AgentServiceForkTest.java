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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceForkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void forksAFinishedRunWithItsWorkspaceHistoryAndEvents() throws Exception {
        AppConfig config = config();
        ScriptedLlm firstLlm = new ScriptedLlm(
                toolResult("write-1", "write_file", Map.of("path", "notes.txt", "content", "parent content")),
                finalResult("parent complete"));

        String parentId;
        try (AgentService service = new AgentService(config, firstLlm)) {
            service.start("Create notes", "parent-session");
            parentId = service.list().get(0).id();
            assertEquals("SUCCEEDED", service.await(parentId, Duration.ofSeconds(20)).status());

            AgentService.ForkResult fork = service.fork(parentId, "child-session");
            assertTrue(fork.runId().startsWith("fork-"));
            assertEquals(parentId, fork.parentRunId());
            assertTrue(fork.workspace().startsWith("child-session-fork-"));
            assertEquals(6, fork.workspace().substring("child-session-fork-".length()).length());

            RunSnapshot snapshot = service.require(fork.runId()).snapshot();
            assertEquals("SUCCEEDED", snapshot.status());
            assertEquals(parentId, snapshot.parentRunId());
            assertNotNull(snapshot.forkSeq());
            assertEquals("parent complete", snapshot.finalAnswer());
            assertTrue(snapshot.forked());

            Path forkedRoot = config.workspacesDir().resolve(fork.workspace());
            assertEquals("parent content", Files.readString(forkedRoot.resolve("notes.txt")));
            assertEquals("parent content",
                    Files.readString(config.workspacesDir().resolve("parent-session/notes.txt")));

            List<ChatMessage> messages = RunArtifacts.loadMessages(forkedRoot);
            assertTrue(messages.stream().anyMatch(m -> m.content() != null
                    && m.content().contains("Create notes")));

            List<AgentEvent> events = service.require(fork.runId()).events().snapshot();
            assertTrue(events.stream().anyMatch(e -> e.type().equals("fork.created")));
            AgentEvent started = events.stream().filter(e -> e.type().equals("run.started"))
                    .findFirst().orElseThrow();
            Map<?, ?> startedSnapshot = (Map<?, ?>) started.data().get("snapshot");
            assertEquals(fork.runId(), startedSnapshot.get("id"));
            assertEquals(fork.workspace(), startedSnapshot.get("workspace"));
            long maxSeq = events.stream().mapToLong(AgentEvent::seq).max().orElse(0);
            assertEquals(events.size(), maxSeq);

            ScriptedLlm secondLlm = new ScriptedLlm(
                    toolResult("write-2", "write_file", Map.of("path", "notes.txt", "content", "child content")),
                    finalResult("child complete"));
            try (AgentService continued = new AgentService(config, secondLlm)) {
                RunSnapshot queued = continued.followUp(fork.runId(), "Change the notes");
                assertEquals("RUNNING", queued.status());
                RunSnapshot finished = continued.await(fork.runId(), Duration.ofSeconds(20));
                assertEquals("SUCCEEDED", finished.status());
                assertEquals("child complete", finished.finalAnswer());
                assertEquals(parentId, finished.parentRunId());
                assertEquals("child content",
                        Files.readString(forkedRoot.resolve("notes.txt")));
                assertTrue(secondLlm.requests.get(0).messages().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("Create notes")));
                assertTrue(secondLlm.requests.get(0).messages().stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("Change the notes")));
            }
        }
    }

    @Test
    void refusesToForkARunningOrUnknownRun() throws Exception {
        AppConfig config = config();
        BlockingLlm llm = new BlockingLlm();
        try (AgentService service = new AgentService(config, llm)) {
            service.start("Wait forever", "busy-session");
            String id = service.list().get(0).id();
            assertTrue(llm.entered.await(10, TimeUnit.SECONDS));

            assertThrows(IllegalStateException.class, () -> service.fork(id, "too-early"));
            assertThrows(NoSuchElementException.class, () -> service.fork("run-missing", "nope"));

            llm.release.countDown();
            assertEquals("SUCCEEDED", service.await(id, Duration.ofSeconds(20)).status());
            assertThrows(IllegalArgumentException.class, () -> service.fork(id, "../escape"));
            assertFalse(service.require(id).running());
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
                new AgentSettings(4, 4, 20_000, 30, true),
                new ServerSettings("127.0.0.1", 0)
        );
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
        private final Deque<ChatResult> results = new ArrayDeque<>();
        final java.util.ArrayList<ChatRequest> requests = new java.util.ArrayList<>();

        @SafeVarargs
        ScriptedLlm(ChatResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            return results.isEmpty() ? finalResult("done") : results.removeFirst();
        }
    }

    private static final class BlockingLlm implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ChatResult chat(ChatRequest request) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return finalResult("released");
        }
    }
}
