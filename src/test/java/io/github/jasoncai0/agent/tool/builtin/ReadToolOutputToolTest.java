package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.ToolOutputRef;
import io.github.jasoncai0.agent.agent.ToolOutputStore;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadToolOutputToolTest {
    @TempDir
    Path temp;

    @Test
    void readsAnExactCharacterWindow() throws IOException {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(workspace.root());
        String output = sequence(40_000);
        ToolOutputRef ref = store.save("run-1", 5, "read_file", output);
        ToolContext context = new ToolContext(workspace, null, null, null, null);

        ToolResult result = new ReadToolOutputTool().execute(context, Map.of(
                "path", ref.relativePath(), "startChar", 30_000, "maxChars", 1_000));

        assertTrue(result.ok());
        assertEquals(output.substring(30_000, 31_000), result.output());
        assertEquals(30_000, result.data().get("startChar"));
        assertEquals(1_000, result.data().get("returnedChars"));
        assertEquals(40_000, result.data().get("totalChars"));
        assertEquals(ref.relativePath(), result.data().get("path"));
    }

    @Test
    void usesTheDefaultWindow() throws IOException {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(workspace.root());
        ToolOutputRef ref = store.save("run-1", 6, "search_text", sequence(40_000));
        ToolContext context = new ToolContext(workspace, null, null, null, null);

        ToolResult result = new ReadToolOutputTool().execute(context,
                Map.of("path", ref.relativePath()));

        assertTrue(result.ok());
        assertEquals(ToolOutputStore.DEFAULT_READ_CHARS, result.output().length());
    }

    @Test
    void returnsEmptyWhenStartIsBeyondEnd() throws IOException {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(workspace.root());
        ToolOutputRef ref = store.save("run-1", 7, "read_file", sequence(40_000));
        ToolContext context = new ToolContext(workspace, null, null, null, null);

        ToolResult result = new ReadToolOutputTool().execute(context, Map.of(
                "path", ref.relativePath(), "startChar", 50_000));

        assertTrue(result.ok());
        assertEquals("", result.output());
        assertEquals(40_000, result.data().get("totalChars"));
    }

    @Test
    void rejectsPathsOutsideTheSpillRoot() throws IOException {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Path dir = Files.createDirectories(workspace.root().resolve(".agent/tool-results/run-1"));
        Files.createSymbolicLink(dir.resolve("link.txt"), outside);
        ToolContext context = new ToolContext(workspace, null, null, null, null);
        ReadToolOutputTool tool = new ReadToolOutputTool();

        assertError(tool, context, "../outside.txt");
        assertError(tool, context, outside.toString());
        assertError(tool, context, ".agent/tool-results/run-1/link.txt");
        assertError(tool, context, ".agent/tool-results/run-1");
        assertError(tool, context, ".agent/tool-results/run-1/missing.txt");
    }

    @Test
    void rejectsOutOfRangeArguments() throws IOException {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        ToolContext context = new ToolContext(workspace, null, null, null, null);
        ReadToolOutputTool tool = new ReadToolOutputTool();

        assertError(tool, context, Map.of("path", ".agent/tool-results/run-1/a.txt", "startChar", -1));
        assertError(tool, context, Map.of("path", ".agent/tool-results/run-1/a.txt", "maxChars", 0));
        assertError(tool, context, Map.of("path", ".agent/tool-results/run-1/a.txt", "maxChars", 65_537));
    }

    @Test
    void isRegisteredAsABuiltInTool() {
        assertEquals(15, BuiltInTools.all().size());
        assertTrue(BuiltInTools.all().stream()
                .anyMatch(tool -> tool.spec().name().equals("read_tool_output")));
    }

    private static void assertError(ReadToolOutputTool tool, ToolContext context, String path) {
        assertError(tool, context, Map.of("path", path));
    }

    private static void assertError(ReadToolOutputTool tool, ToolContext context, Map<String, Object> args) {
        ToolResult result = tool.execute(context, args);
        assertFalse(result.ok(), "expected an error for " + args);
        assertFalse(result.output().isBlank());
    }

    private static String sequence(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append((char) ('a' + (i % 26)));
        }
        return text.toString();
    }
}
