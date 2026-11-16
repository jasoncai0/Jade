package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentSettings SETTINGS = new AgentSettings(4, 4, 20_000, 30, true);

    @TempDir
    Path temp;

    @Test
    void writesCompilesRunsAndCompletesTheTenPhaseLoop() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hello-agent");
                    }
                }
                """;
        llm.script.add(toolResult("call-write", "write_file",
                Map.of("path", "src/Main.java", "content", code)));
        llm.script.add(toolResult("call-run", "run_java", Map.of("mainClass", "Main")));
        llm.script.add(finalResult("Implemented and ran it."));

        Harness harness = harness(llm);
        RunSnapshot snapshot = harness.loop.run("Create a Java CLI that prints hello-agent",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals("Implemented and ran it.", snapshot.finalAnswer());
        assertTrue(Files.isRegularFile(harness.workspace.root().resolve("src/Main.java")));
        assertTrue(snapshot.files().contains("src/Main.java"));
        assertTrue(snapshot.verification().contains("Already verified"));
        assertTrue(snapshot.transcriptPath() != null
                && Files.isRegularFile(harness.workspace.root().resolve(snapshot.transcriptPath())));

        List<AgentEvent> events = harness.events.snapshot();
        List<String> phaseIds = events.stream()
                .filter(event -> event.type().equals("phase"))
                .map(event -> String.valueOf(event.data().get("phase")))
                .toList();
        assertEquals(30, phaseIds.size());
        for (AgentPhase phase : AgentPhase.values()) {
            assertTrue(phaseIds.contains(phase.id()), "missing phase " + phase.id());
        }
        String runOutput = events.stream()
                .filter(event -> event.type().equals("tool.end"))
                .filter(event -> "run_java".equals(event.data().get("name")))
                .map(event -> String.valueOf(event.data().get("output")))
                .findFirst().orElse("");
        assertTrue(runOutput.contains("hello-agent"), runOutput);
        List<AgentEvent> llmRequests = events.stream()
                .filter(event -> event.type().equals("llm.request"))
                .toList();
        assertEquals(3, llmRequests.size());
        assertTrue(String.valueOf(llmRequests.get(0).data().get("body")).contains("\"model\":\"fake\""));
        assertEquals(4, snapshot.maxSteps());
    }

    @Test
    void feedsRealCompileFailuresBackToTheModelAndRepairs() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(toolResult("call-bad", "write_file", Map.of("path", "src/Main.java",
                "content", "public class Main { public static void main(String[] a) { "
                        + "System.out.println(\"bad\") } }\n")));
        llm.script.add(finalResult("done"));
        llm.script.add(toolResult("call-fix", "write_file", Map.of("path", "src/Main.java",
                "content", "public class Main { public static void main(String[] a) { "
                        + "System.out.println(\"fixed\"); } }\n")));
        llm.script.add(finalResult("Fixed and verified."));

        Harness harness = harness(llm);
        RunSnapshot snapshot = harness.loop.run("Fix the Java program", harness.workspace,
                harness.state, harness.events, harness.control);

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals(4, llm.requests.size());
        assertTrue(llm.requests.stream()
                .flatMap(request -> request.messages().stream())
                .anyMatch(message -> message.content() != null
                        && message.content().contains("Automatic verification failed")));
        List<AgentEvent> verifications = harness.events.snapshot().stream()
                .filter(event -> event.type().equals("verification"))
                .toList();
        assertTrue(verifications.stream().anyMatch(event -> Boolean.FALSE.equals(event.data().get("ok"))));
        assertTrue(verifications.stream().anyMatch(event -> Boolean.TRUE.equals(event.data().get("ok"))));
        assertTrue(snapshot.verification().contains("javac compiled"));
        assertTrue(harness.workspace.readText("src/Main.java").contains("fixed"));
    }

    @Test
    void writesAndRunsPythonThroughTheGenericTools() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(toolResult("call-python-write", "write_file", Map.of(
                "path", "main.py",
                "content", "print('hello-python')\n")));
        llm.script.add(toolResult("call-python-run", "run_code", Map.of(
                "language", "python",
                "entry", "main.py")));
        llm.script.add(finalResult("Ran the Python program."));

        Harness harness = harness(llm);
        RunSnapshot snapshot = harness.loop.run("Create a Python CLI that prints hello-python",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("SUCCEEDED", snapshot.status());
        assertTrue(snapshot.files().contains("main.py"));
        assertTrue(snapshot.verification().contains("Already verified"));
        String output = harness.events.snapshot().stream()
                .filter(event -> event.type().equals("tool.end"))
                .filter(event -> "run_code".equals(event.data().get("name")))
                .map(event -> String.valueOf(event.data().get("output")))
                .findFirst().orElse("");
        assertTrue(output.contains("hello-python"), output);
    }

    @Test
    void unlimitedTurnsReachCompletionWithoutMaxSteps() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(toolResult("call-write", "write_file", Map.of(
                "path", "src/Main.java",
                "content", "public class Main { public static void main(String[] a) { System.out.println(\"unbounded\"); } }")));
        llm.script.add(finalResult("Unbounded run finished."));

        AgentSettings unbounded = settings(0, 0);
        Harness harness = harness(llm, unbounded);
        RunSnapshot snapshot = harness.loop.run("Run without a hard turn cap",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals(0, snapshot.maxSteps());
        assertEquals("Unbounded run finished.", snapshot.finalAnswer());
    }

    @Test
    void repeatedIdenticalToolCallIsStoppedByLoopGuard() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 5; i++) {
            llm.script.add(toolResult("call-loop-" + i, "list_files", Map.of("path", ".")));
        }

        Harness harness = harness(llm, settings(0, 0));
        RunSnapshot snapshot = harness.loop.run("Loop forever",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("LOOP_DETECTED", snapshot.status());
        assertTrue(snapshot.error().contains("repeated 4 times"), snapshot.error());
        assertTrue(harness.events.snapshot().stream()
                .anyMatch(event -> event.type().equals("loop.detected")));
    }


    @Test
    void variedToolCallsWithoutWorkspaceProgressStopAtNoProgressLimit() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 17; i++) {
            llm.script.add(toolResult("call-stall-" + i, "list_files", Map.of("path", "missing-" + i)));
        }

        Harness harness = harness(llm, settings(0, 0));
        RunSnapshot snapshot = harness.loop.run("Keep looking without changing anything",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("LOOP_DETECTED", snapshot.status());
        assertTrue(snapshot.error().contains("16 consecutive"), snapshot.error());
        assertEquals(16, llm.requests.size());
        assertTrue(harness.events.snapshot().stream()
                .anyMatch(event -> event.type().equals("reflection.requested")));
        assertTrue(harness.events.snapshot().stream()
                .anyMatch(event -> event.type().equals("loop.detected")));
    }

    @Test
    void tokenBudgetStopsRunBeforeAnotherModelTurn() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(toolResult("call-budget-1", "list_files", Map.of("path", ".")));
        llm.script.add(toolResult("call-budget-2", "write_file", Map.of(
                "path", "src/Budget.java", "content", "public class Budget {}")));

        Harness harness = harness(llm, settings(0, 25));
        RunSnapshot snapshot = harness.loop.run("Spend the budget",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("BUDGET_EXCEEDED", snapshot.status());
        assertTrue(snapshot.error().contains("token budget"), snapshot.error());
        assertEquals(2, llm.requests.size());
    }

    @Test
    void explicitPositiveCapStillWorks() throws Exception {
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(toolResult("call-cap-1", "list_files", Map.of("path", ".")));
        llm.script.add(toolResult("call-cap-2", "list_files", Map.of("path", "src")));

        Harness harness = harness(llm, settings(2, 0));
        RunSnapshot snapshot = harness.loop.run("Use the explicit cap",
                harness.workspace, harness.state, harness.events, harness.control);

        assertEquals("MAX_STEPS", snapshot.status());
        assertEquals(2, snapshot.step());
    }

    private static AgentSettings settings(int maxSteps, long tokenBudget) {
        return new AgentSettings(maxSteps, 4, 20_000, 30, true, PermissionMode.BYPASS, tokenBudget);
    }

    private Harness harness(LlmClient llm) {
        return harness(llm, SETTINGS);
    }

    private Harness harness(LlmClient llm, AgentSettings settings) {
        try {
            Path root = temp.resolve("ws-" + System.nanoTime());
            Files.createDirectories(root.resolve(".agent-build/classes"));
            Workspace workspace = Workspace.open(root);
            Path skills = temp.resolve("skills");
            Files.createDirectories(skills);
            SkillRegistry registry = SkillRegistry.load(skills);
            RunState state = new RunState("run-test", "task", "ws", settings.maxSteps());
            RunEventLog events = new RunEventLog();
            RunControl control = new RunControl();
            return new Harness(
                    new AgentLoop(llm, BuiltInTools.registry(), registry, settings, 1024),
                    workspace, state, events, control);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ChatResult toolResult(String id, String name, Map<String, Object> args) throws Exception {
        return new ChatResult(null, null,
                List.of(new ToolCall(id, name, JSON.writeValueAsString(args))),
                "tool_calls", new Usage(10, 5, 15));
    }

    private static ChatResult finalResult(String content) {
        return new ChatResult(content, null, List.of(), "stop", new Usage(20, 8, 28));
    }

    private record Harness(AgentLoop loop, Workspace workspace, RunState state,
                           RunEventLog events, RunControl control) {
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> script = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            ChatResult next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted LLM response left");
            }
            return next;
        }

        @Override
        public String requestBody(ChatRequest request) {
            return "{\"model\":\"fake\",\"messages\":" + request.messages().size()
                    + ",\"tools\":" + request.tools().size() + "}";
        }
    }
}
