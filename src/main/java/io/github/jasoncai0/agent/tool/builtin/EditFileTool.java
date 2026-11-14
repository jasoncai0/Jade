package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.Map;

public final class EditFileTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "edit_file",
                "Replace an exact block of text in an existing file. Prefer this over rewriting a whole file. "
                        + "If the old text occurs more than once, pass replaceAll=true or include more context.",
                Schemas.object(Schemas.properties(
                        "path", Schemas.string("Relative path to edit."),
                        "oldText", Schemas.string("Exact text to replace."),
                        "newText", Schemas.string("Replacement text. Empty string deletes the block."),
                        "replaceAll", Schemas.bool("Replace every occurrence instead of requiring a unique match.")
                ), "path", "oldText", "newText")
        );
    }

    @Override
    public boolean mutatesWorkspace() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String path = ToolArgs.requiredString(args, "path");
        String oldText = ToolArgs.requiredString(args, "oldText");
        String newText = ToolArgs.string(args, "newText", "");
        boolean replaceAll = ToolArgs.bool(args, "replaceAll", false);
        String current = context.workspace().readText(path);
        int occurrences = count(current, oldText);
        if (occurrences == 0) {
            return ToolResult.error("oldText was not found in " + path
                    + ". Read the file again and copy the exact block.");
        }
        if (occurrences > 1 && !replaceAll) {
            return ToolResult.error("oldText occurs " + occurrences + " times in " + path
                    + ". Include more surrounding context or pass replaceAll=true.");
        }
        String updated = replaceAll
                ? current.replace(oldText, newText)
                : current.replaceFirst(java.util.regex.Pattern.quote(oldText),
                        java.util.regex.Matcher.quoteReplacement(newText));
        if (updated.equals(current)) {
            return ToolResult.ok("No change: replacement produced identical content.");
        }
        context.workspace().writeText(path, updated);
        context.markDirty();
        context.refreshFiles();
        return ToolResult.ok("Edited " + path + " (" + occurrences + " occurrence(s), "
                        + updated.length() + " chars).",
                Map.of("path", path, "occurrences", occurrences));
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += Math.max(1, needle.length());
        }
        return count;
    }
}
