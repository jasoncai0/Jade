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
import io.github.jasoncai0.agent.llm.ToolSpec;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void delegatesAReadOnlyTaskAndReturnsOnlyTheSummary() throws Exception {
        AppConfig config = config();
        ScriptedLlm llm = new ScriptedLlm(
                toolResult("delegate-1", "delegate_task", Map.of(
                        "task", "Inspect the repository layout and report the main packages",
                        "name", "scout")),
                finalResult("Three packages: agent, tool, web."),
                finalResult("Parent answer based on the child summary."));

        try (AgentService service = new AgentService(config, llm)) {
            service.start("Explain the repository layout", "subagent-parent");
            String parentId = service.list().get(0).id();
            RunSnapshot done = service.await(parentId, Duration.ofSeconds(20));

            assertEquals("SUCCEEDED", done.status());
            assertEquals(3, llm.requests.size());
            List<String> childTools = llm.requests.get(1).tools().stream()
                    .map(ToolSpec::name).toList();
            assertTrue(childTools.contains("read_file"));
            assertTrue(childTools.contains("search_text"));
            assertFalse(childTools.contains("write_file"));
            assertFalse(childTools.contains("run_command"));
            assertFalse(childTools.contains("delegate_task"));

            AgentRun parent = service.require(parentId);
            List<AgentEvent> events = parent.events().snapshot();
            assertEquals(1, events.stream().filter(e -> e.type().equals("subagent.start")).count());
            assertEquals(1, events.stream().filter(e -> e.type().equals("subagent.end")).count());
            String childId = service.list().stream()
                    .map(RunSnapshot::id)
                    .filter(id -> id.startsWith("sub-"))
                    .findFirst().orElseThrow();
            assertNotNull(service.require(childId));
            assertEquals(28, parent.state().subagentUsage().totalTokens());
            assertTrue(parent.state().subagentUsage().totalTokens() > 0);
            assertEquals("SUCCEEDED", service.require(childId).snapshot().status());
            assertTrue(Files.isRegularFile(config.workspacesDir()
                    .resolve("subagent-parent/.agent/transcript.json")));
        }
    }

    @Test
    void childCannotCallWriteToolsEvenWhenItGuessesTheName() throws Exception {
        AppConfig config = config();
        ScriptedLlm llm = new ScriptedLlm(
                toolResult("delegate-2", "delegate_task", Map.of(
                        "task", "Try to create a file; this must be denied", "name", "scout")),
                toolResult("write-1", "write_file", Map.of("path", "src/Bad.java", "content", "bad")),
                finalResult("The write tool is not available."),
                finalResult("Parent done."));

        try (AgentService service = new AgentService(config, llm)) {
            service.start("Test read-only child", "subagent-readonly");
            String parentId = service.list().get(0).id();
            RunSnapshot done = service.await(parentId, Duration.ofSeconds(20));
            assertEquals("SUCCEEDED", done.status());

            String childId = service.list().stream().map(RunSnapshot::id)
                    .filter(id -> id.startsWith("sub-")).findFirst().orElseThrow();
            AgentRun child = service.require(childId);
            List<ChatMessage> messages = RunArtifacts.loadMessages(child.workspace().root(), childId);
            assertTrue(messages.stream().anyMatch(m -> m.content() != null
                    && m.content().contains("Permission denied")), messages.toString());
            List<String> childTools = llm.requests.get(1).tools().stream()
                    .map(ToolSpec::name).toList();
            assertFalse(childTools.contains("write_file"));
            assertFalse(childTools.contains("run_command"));
            assertFalse(Files.exists(config.workspacesDir().resolve("subagent-readonly/src/Bad.java")));
        }
    }


    @Test
    void rejectsNestedDelegation() throws Exception {
        try (AgentService service = new AgentService(config(), new ScriptedLlm(finalResult("ok")))) {
            service.start("parent", "nested-parent");
            AgentRun parent = service.require(service.list().get(0).id());
            AgentRun child = service.newChildRun(parent, "scout", "child task");
            SubagentService children = new SubagentService(service);
            assertThrows(IllegalArgumentException.class,
                    () -> children.start(child, "nested", "scout", 60));
        }
    }

    @Test
    void capsConcurrentChildrenAtTwoPerParent() throws Exception {
        SlowLlm llm = new SlowLlm();
        try (AgentService service = new AgentService(config(), llm)) {
            service.start("parent", "parallel-parent");
            String parentId = service.list().get(0).id();
            service.await(parentId, Duration.ofSeconds(10));
            AgentRun parent = service.require(parentId);
            SubagentService children = new SubagentService(service);
            List<Thread> threads = new ArrayList<>();
            List<SubagentService.DelegationResult> results =
                    java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 3; i++) {
                final int index = i;
                Thread thread = new Thread(() -> results.add(
                        children.start(parent, "task " + index, "scout", 60)));
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(15_000);
            }
            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(r -> r.status().equals("succeeded")));
            assertTrue(llm.peak.get() <= 2, "peak concurrent children was " + llm.peak.get());
            children.close();
        }
    }

    private static final class SlowLlm implements LlmClient {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        @Override
        public ChatResult chat(ChatRequest request) {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return finalResult("child done");
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
                new ServerSettings("127.0.0.1", 0));
    }

    private static ChatResult toolResult(String id, String name, Map<String, Object> args) throws Exception {
        return new ChatResult(null, null, List.of(new ToolCall(id, name,
                JSON.writeValueAsString(args))), "tool_calls", new Usage(10, 5, 15));
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
        public synchronized ChatResult chat(ChatRequest request) {
            requests.add(request);
            ChatResult next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response left");
            }
            return next;
        }
    }
}
