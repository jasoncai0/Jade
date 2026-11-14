package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
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

    private Harness harness(LlmClient llm) {
        try {
            Path root = temp.resolve("ws");
            Files.createDirectories(root.resolve(".agent-build/classes"));
            Workspace workspace = Workspace.open(root);
            Path skills = temp.resolve("skills");
            Files.createDirectories(skills);
            SkillRegistry registry = SkillRegistry.load(skills);
            RunState state = new RunState("run-test", "task", "ws", SETTINGS.maxSteps());
            RunEventLog events = new RunEventLog();
            RunControl control = new RunControl();
            return new Harness(
                    new AgentLoop(llm, BuiltInTools.registry(), registry, SETTINGS, 1024),
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
