package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchTextTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "search_text",
                "Search text files for a literal string or regular expression. Returns path:line: text matches.",
                Schemas.object(Schemas.properties(
                        "query", Schemas.string("Text or regular expression to search for."),
                        "path", Schemas.string("Relative directory to search. Defaults to the workspace root."),
                        "regex", Schemas.bool("Treat query as a Java regular expression."),
                        "maxResults", Schemas.integer("Maximum matches to return.", 1, 200)
                ), "query")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String query = ToolArgs.requiredString(args, "query");
        String relative = ToolArgs.string(args, "path", ".");
        boolean regex = ToolArgs.bool(args, "regex", false);
        int maxResults = ToolArgs.integer(args, "maxResults", 50, 1, 200);
        Workspace workspace = context.workspace();
        workspace.resolve(relative);

        Pattern pattern;
        try {
            pattern = Pattern.compile(regex ? query : Pattern.quote(query),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            return ToolResult.error("Invalid regex: " + e.getDescription());
        }

        StringBuilder out = new StringBuilder();
        int matches = 0;
        int filesScanned = 0;
        List<Workspace.FileEntry> entries = workspace.listFiles(2000, 12);
        for (Workspace.FileEntry entry : entries) {
            if (entry.directory() || matches >= maxResults) {
                continue;
            }
            if (!relative.equals(".") && !entry.path().startsWith(relative.replace('\\', '/') + "/")
                    && !entry.path().equals(relative)) {
                continue;
            }
            if (entry.size() > 512 * 1024) {
                continue;
            }
            String content;
            try {
                content = workspace.readText(entry.path());
            } catch (RuntimeException ignored) {
                continue;
            }
            filesScanned++;
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length && matches < maxResults; i++) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.find()) {
                    matches++;
                    String text = lines[i].strip();
                    if (text.length() > 240) {
                        text = text.substring(0, 240) + "…";
                    }
                    out.append(entry.path()).append(':').append(i + 1).append(": ").append(text).append('\n');
                }
            }
        }
        String result = out.toString().stripTrailing();
        if (matches == 0) {
            return ToolResult.ok("No matches for '" + query + "' in " + relative + " (" + filesScanned + " files scanned).",
                    Map.of("matches", 0, "filesScanned", filesScanned));
        }
        return ToolResult.ok(result + "\n(" + matches + " match(es), " + filesScanned + " files scanned)",
                Map.of("matches", matches, "filesScanned", filesScanned));
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }
}
