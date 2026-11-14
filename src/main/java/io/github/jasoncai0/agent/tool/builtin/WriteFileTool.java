package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.Map;

public final class WriteFileTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "write_file",
                "Create or overwrite a text file. Always write complete, compilable content. Parent directories are created.",
                Schemas.object(Schemas.properties(
                        "path", Schemas.string("Relative path to write."),
                        "content", Schemas.string("Full UTF-8 file content.")
                ), "path", "content")
        );
    }

    @Override
    public boolean mutatesWorkspace() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String path = ToolArgs.requiredString(args, "path");
        String content = ToolArgs.requiredString(args, "content");
        int bytes = context.workspace().writeText(path, content);
        context.markDirty();
        context.refreshFiles();
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        return ToolResult.ok("Wrote " + path + " (" + bytes + " bytes, " + lines + " lines).",
                Map.of("path", path, "bytes", bytes, "lines", lines));
    }
}
