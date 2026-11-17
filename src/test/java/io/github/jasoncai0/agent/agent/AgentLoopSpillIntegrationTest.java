package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopSpillIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentSettings SETTINGS = new AgentSettings(
            4, 4, 20_000, 30, true, PermissionMode.BYPASS, 0);

    @TempDir
    Path temp;

    @Test
    void largeToolOutputKeepsHeadAndTailAndIsRetrievable() throws Exception {
        String output = "FIRST LINE\n" + "x".repeat(34_970) + "\nLAST_ERROR: expected true";
        Harness harness = harness(new LargeOutputTool(output));

        RunSnapshot snapshot = harness.loop().run("trigger a large tool output",
                harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        String visible = toolEndOutput(harness.events(), "big_output");
        assertTrue(visible.startsWith("FIRST LINE\n"), visible.substring(0, 40));
        assertTrue(visible.endsWith("LAST_ERROR: expected true"));
        assertTrue(visible.contains("[output truncated: " + output.length() + " chars"));
        assertTrue(visible.contains("read with read_tool_output"));

        String message = toolMessage(harness.llm().requests.get(1), "big_output");
        assertTrue(message.contains(visible.substring(0, 100)));
        assertTrue(message.contains("LAST_ERROR: expected true"));

        String relative = extractSpillPath(visible);
        Path spill = harness.workspace().root().resolve(relative);
        assertTrue(Files.isRegularFile(spill), "missing spill file " + spill);
        assertEquals(output, Files.readString(spill));
    }

    @Test
    void twoCallsToTheSameToolInOneBatchGetSeparateSpillFiles() throws Exception {
        String output = "FIRST LINE\n" + "z".repeat(34_970) + "\nLAST_ERROR: shared";
        Harness harness = harness(new LargeOutputTool(output));
        harness.llm().script.clear();
        harness.llm().script.add(new ChatResult(null, null,
                List.of(new ToolCall("call-a", "big_output", "{}"),
                        new ToolCall("call-b", "big_output", "{}")),
                "tool_calls", new Usage(10, 5, 15)));
        harness.llm().script.add(new ChatResult("done", null, List.of(), "stop", new Usage(20, 8, 28)));

        RunSnapshot snapshot = harness.loop().run("trigger two large outputs",
                harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        List<String> paths = toolEndOutputs(harness.events(), "big_output").stream()
                .map(AgentLoopSpillIntegrationTest::extractSpillPath)
                .toList();
        assertEquals(2, paths.size());
        assertTrue(!paths.get(0).equals(paths.get(1)), paths.toString());
        for (String relative : paths) {
            Path spill = harness.workspace().root().resolve(relative);
            assertTrue(Files.isRegularFile(spill), "missing spill file " + spill);
            assertEquals(output, Files.readString(spill));
        }
    }

    @Test
    void outputUnderTheLimitStaysUnchangedAndWritesNoFile() throws Exception {
        String output = "small line\n" + "y".repeat(29_000);
        Harness harness = harness(new LargeOutputTool(output));

        RunSnapshot snapshot = harness.loop().run("trigger a small tool output",
                harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        String visible = toolEndOutput(harness.events(), "big_output");
        assertEquals(output, visible);
        assertFalse(Files.exists(harness.workspace().root().resolve(".agent/tool-results")));
    }

    private static String toolEndOutput(RunEventLog events, String name) {
        return toolEndOutputs(events, name).get(0);
    }

    private static List<String> toolEndOutputs(RunEventLog events, String name) {
        return events.snapshot().stream()
                .filter(event -> event.type().equals("tool.end"))
                .filter(event -> name.equals(event.data().get("name")))
                .map(event -> String.valueOf(event.data().get("output")))
                .toList();
    }

    private static String toolMessage(ChatRequest request, String name) {
        return request.messages().stream()
                .filter(message -> message.role() == io.github.jasoncai0.agent.llm.Role.TOOL)
                .filter(message -> message.content() != null && message.content().contains(name))
                .map(message -> message.content())
                .findFirst()
                .orElseThrow();
    }

    private static String extractSpillPath(String visible) {
        int start = visible.indexOf("full text: ") + "full text: ".length();
        int end = visible.indexOf("; read with read_tool_output", start);
        return visible.substring(start, end);
    }

    private Harness harness(Tool tool) throws Exception {
        Path root = temp.resolve("ws-" + System.nanoTime());
        Files.createDirectories(root.resolve(".agent-build/classes"));
        Workspace workspace = Workspace.open(root);
        Path skills = temp.resolve("skills-" + System.nanoTime());
        Files.createDirectories(skills);
        ToolRegistry registry = BuiltInTools.registry();
        registry.register(tool);
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(new ChatResult(null, null,
                List.of(new ToolCall("call-big", tool.spec().name(), "{}")),
                "tool_calls", new Usage(10, 5, 15)));
        llm.script.add(new ChatResult("done", null, List.of(), "stop", new Usage(20, 8, 28)));
        AgentLoop loop = new AgentLoop(llm, registry, SkillRegistry.load(skills), SETTINGS, 1024);
        return new Harness(loop, workspace,
                new RunState("run-test", "task", "ws", SETTINGS.maxSteps()),
                new RunEventLog(), new RunControl(), llm);
    }

    private record Harness(AgentLoop loop, Workspace workspace, RunState state,
                           RunEventLog events, RunControl control, ScriptedLlm llm) {
    }

    private static final class LargeOutputTool implements Tool {
        private final String output;

        private LargeOutputTool(String output) {
            this.output = output;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec("big_output", "Return a large output.",
                    Map.of("type", "object", "properties", Map.of()));
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            return ToolResult.ok(output);
        }
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
    }
}
