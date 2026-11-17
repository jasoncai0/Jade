package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.Map;

public final class ReadFileTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "read_file",
                "Read a UTF-8 text file from the workspace with line numbers. Read before editing.",
                Schemas.object(Schemas.properties(
                        "path", Schemas.string("Relative path of the file to read."),
                        "startLine", Schemas.integer("First line to return, 1-based.", 1, 1_000_000),
                        "maxLines", Schemas.integer("Maximum number of lines to return.", 1, 800)
                ), "path")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String path = ToolArgs.requiredString(args, "path");
        int startLine = ToolArgs.integer(args, "startLine", 1, 1, 1_000_000);
        int maxLines = ToolArgs.integer(args, "maxLines", 300, 1, 800);
        String content = context.workspace().readText(path);
        String numbered = ToolOutput.numbered(content, startLine, maxLines);
        return ToolResult.ok(numbered.isBlank() ? "(empty file)" : numbered,
                Map.of("path", path, "lines", content.split("\n", -1).length));
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }
}
