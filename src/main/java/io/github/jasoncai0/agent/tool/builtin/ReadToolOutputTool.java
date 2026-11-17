package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.ToolOutputStore;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.WorkspaceException;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ReadToolOutputTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "read_tool_output",
                "Read a character window from a large tool output that was spilled to "
                        + ".agent/tool-results/. Use this when a tool result says it was truncated.",
                Schemas.object(Schemas.properties(
                        "path", Schemas.string("Spill path shown in the truncation marker."),
                        "startChar", Schemas.integer("First character to return, 0-based.", 0,
                                (int) ToolOutputStore.MAX_READ_START),
                        "maxChars", Schemas.integer("Maximum characters to return.", 1,
                                ToolOutputStore.MAX_READ_CHARS)
                ), "path")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        try {
            String path = ToolArgs.requiredString(args, "path");
            int startChar = ToolArgs.integer(args, "startChar", 0, 0,
                    (int) ToolOutputStore.MAX_READ_START);
            int maxChars = ToolArgs.integer(args, "maxChars", ToolOutputStore.DEFAULT_READ_CHARS,
                    1, ToolOutputStore.MAX_READ_CHARS);
            ToolOutputStore store = new ToolOutputStore(context.workspace().root());
            String text = store.read(path, startChar, maxChars);
            int totalChars = store.totalChars(path);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path);
            data.put("startChar", startChar);
            data.put("returnedChars", text.length());
            data.put("totalChars", totalChars);
            return ToolResult.ok(text, data);
        } catch (WorkspaceException | IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
