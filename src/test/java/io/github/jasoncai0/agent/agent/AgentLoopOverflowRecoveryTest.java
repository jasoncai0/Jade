package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.LlmException;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopOverflowRecoveryTest {
    private static final AgentSettings SETTINGS = new AgentSettings(
            4, 4, 20_000, 30, true, PermissionMode.BYPASS, 0, false, 30_000, true);

    @TempDir
    Path temp;

    @Test
    void overflowTriggersOneCompactionAndOneRetry() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(new LlmException("maximum context length exceeded", 400,
                "context_length_exceeded", null));
        llm.script.add(new ChatResult("earlier conversation summary", null, List.of(),
                "stop", new Usage(20, 10, 30)));
        llm.script.add(new ChatResult("finished", null, List.of(), "stop", new Usage(10, 5, 15)));

        Harness harness = harness(llm, SETTINGS);
        RunSnapshot snapshot = harness.loop().continueSession("continue the task",
                history(), harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals("finished", snapshot.finalAnswer());
        assertEquals(1, count(harness.events(), "context.overflow"));
        assertEquals(1, count(harness.events(), "context.compacted"));
        assertEquals(3, llm.requests.size());
        ChatRequest retried = llm.requests.get(2);
        assertTrue(retried.messages().stream()
                .anyMatch(message -> "continue the task".equals(message.content())));
        assertTrue(retried.messages().stream()
                .anyMatch(message -> "original task".equals(message.content())));
        assertTrue(harness.events().snapshot().stream()
                .anyMatch(event -> event.type().equals("context.compacted")
                        && Number.class.isInstance(event.data().get("summaryTokens"))));
    }

    @Test
    void secondOverflowFailsWithoutASecondRetry() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(overflow());
        llm.script.add(new ChatResult("summary", null, List.of(), "stop", new Usage(1, 1, 2)));
        llm.script.add(overflow());

        Harness harness = harness(llm, SETTINGS);
        RunSnapshot snapshot = harness.loop().continueSession("continue the task",
                history(), harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("FAILED", snapshot.status());
        assertTrue(snapshot.error().contains("Context still exceeds the model window"), snapshot.error());
        assertEquals(3, llm.requests.size());
        assertEquals(1, count(harness.events(), "context.overflow"));
        assertEquals(1, count(harness.events(), "context.compacted"));
    }

    @Test
    void ordinaryBadRequestFailsImmediately() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(new LlmException("invalid request", 400, "invalid_request_error", null));

        Harness harness = harness(llm, SETTINGS);
        RunSnapshot snapshot = harness.loop().run("do it", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("FAILED", snapshot.status());
        assertEquals(1, llm.requests.size());
        assertEquals(0, count(harness.events(), "context.overflow"));
    }

    @Test
    void disabledRecoveryKeepsFailFast() throws Exception {
        AgentSettings noRecovery = new AgentSettings(
                4, 4, 20_000, 30, true, PermissionMode.BYPASS, 0, false, 30_000, false);
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(overflow());

        Harness harness = harness(llm, noRecovery);
        RunSnapshot snapshot = harness.loop().run("do it", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("FAILED", snapshot.status());
        assertEquals(1, llm.requests.size());
        assertEquals(0, count(harness.events(), "context.overflow"));
    }

    private static LlmException overflow() {
        return new LlmException("too many tokens", 400, "context_length_exceeded", null);
    }

    private static long count(RunEventLog events, String type) {
        return events.snapshot().stream().filter(event -> event.type().equals(type)).count();
    }

    private static List<ChatMessage> history() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("system prompt"));
        history.add(ChatMessage.user("original task"));
        history.add(new ChatMessage(Role.ASSISTANT, "reading", null,
                List.of(new ToolCall("call-1", "read_file", "{\"path\":\"src/Main.java\"}")), null));
        history.add(ChatMessage.tool("call-1", "old output ".repeat(500)));
        history.add(ChatMessage.user("earlier follow-up"));
        return history;
    }

    private Harness harness(LlmClient llm, AgentSettings settings) throws Exception {
        Path root = temp.resolve("ws-" + System.nanoTime());
        Files.createDirectories(root.resolve(".agent-build/classes"));
        Workspace workspace = Workspace.open(root);
        Path skills = temp.resolve("skills-" + System.nanoTime());
        Files.createDirectories(skills);
        return new Harness(
                new AgentLoop(llm, BuiltInTools.registry(), SkillRegistry.load(skills), settings, 1024),
                workspace,
                new RunState("run-overflow", "original task", "ws", settings.maxSteps()),
                new RunEventLog(),
                new RunControl());
    }

    private record Harness(AgentLoop loop, Workspace workspace, RunState state,
                           RunEventLog events, RunControl control) {
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<Object> script = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            Object next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response left");
            }
            if (next instanceof LlmException error) {
                throw error;
            }
            return (ChatResult) next;
        }
    }
}
