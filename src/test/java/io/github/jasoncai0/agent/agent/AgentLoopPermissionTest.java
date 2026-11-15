package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionScope;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopPermissionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentSettings SETTINGS = new AgentSettings(
            3, 3, 20_000, 30, true, PermissionMode.BALANCED);

    @TempDir
    Path temp;

    @Test
    void waitsForApprovalBeforeWritingAndContinuesAfterAllow() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(write("call-write"));
        llm.script.add(finalAnswer("Wrote the file."));
        Harness harness = harness(llm);

        CompletableFuture<RunSnapshot> future = CompletableFuture.supplyAsync(
                () -> harness.loop.run("Write a file", harness.workspace,
                        harness.state, harness.events, harness.control));
        PermissionRequest request = awaitRequest(harness.broker, "run-perm");
        assertTrue(harness.state.pendingPermission() != null);
        assertEquals("WAITING_APPROVAL", harness.state.status());
        harness.broker.resolve(request.id(), PermissionDecision.ALLOW, PermissionScope.ONCE, "test");

        RunSnapshot snapshot = future.get(5, TimeUnit.SECONDS);
        assertEquals("SUCCEEDED", snapshot.status());
        assertTrue(Files.isRegularFile(harness.workspace.root().resolve("src/Main.java")));
        assertTrue(harness.events.snapshot().stream().anyMatch(e -> e.type().equals("permission.request")));
        assertTrue(harness.events.snapshot().stream().anyMatch(e -> e.type().equals("permission.resolved")));
        assertTrue(Files.isRegularFile(harness.workspace.root().resolve(".agent/permissions.jsonl")));
    }

    @Test
    void denialBecomesToolErrorAndDoesNotWriteTheFile() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(write("call-write"));
        llm.script.add(finalAnswer("Write was denied."));
        Harness harness = harness(llm);

        CompletableFuture<RunSnapshot> future = CompletableFuture.supplyAsync(
                () -> harness.loop.run("Write a file", harness.workspace,
                        harness.state, harness.events, harness.control));
        PermissionRequest request = awaitRequest(harness.broker, "run-perm");
        harness.broker.resolve(request.id(), PermissionDecision.DENY, PermissionScope.ONCE, "test");
        RunSnapshot snapshot = future.get(5, TimeUnit.SECONDS);

        assertEquals("SUCCEEDED", snapshot.status());
        assertFalse(Files.exists(harness.workspace.root().resolve("src/Main.java")));
        assertTrue(harness.events.snapshot().stream()
                .filter(e -> e.type().equals("tool.end"))
                .anyMatch(e -> Boolean.FALSE.equals(e.data().get("ok"))));
    }

    private PermissionRequest awaitRequest(PermissionBroker broker, String runId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            PermissionRequest request = broker.pendingFor(runId);
            if (request != null) {
                return request;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No permission request was registered");
    }

    private Harness harness(LlmClient llm) throws Exception {
        Path root = temp.resolve("ws");
        Files.createDirectories(root);
        Workspace workspace = Workspace.open(root);
        Path skills = temp.resolve("skills");
        Files.createDirectories(skills);
        RunState state = new RunState("run-perm", "write a file", "ws", 3);
        RunEventLog events = new RunEventLog();
        RunControl control = new RunControl();
        PermissionBroker broker = new PermissionBroker();
        PermissionSession session = new PermissionSession();
        AgentLoop loop = new AgentLoop(llm, BuiltInTools.registry(), SkillRegistry.load(skills),
                SETTINGS, 1024, new Verifier(), broker, session);
        return new Harness(loop, workspace, state, events, control, broker, session);
    }

    private static ChatResult write(String id) throws Exception {
        return new ChatResult(null, null,
                List.of(new ToolCall(id, "write_file", JSON.writeValueAsString(Map.of(
                        "path", "src/Main.java", "content", "public class Main {}")))),
                "tool_calls", new Usage(10, 5, 15));
    }

    private static ChatResult finalAnswer(String content) {
        return new ChatResult(content, null, List.of(), "stop", new Usage(20, 8, 28));
    }

    private record Harness(AgentLoop loop, Workspace workspace, RunState state,
                           RunEventLog events, RunControl control,
                           PermissionBroker broker, PermissionSession permissionSession) {
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> script = new ArrayDeque<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            ChatResult next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response");
            }
            return next;
        }
    }
}
