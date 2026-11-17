package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ListFilesTool implements Tool {
    private static final Set<String> SKIP = Set.of(
            ".git", ".idea", ".gradle", ".mvn", ".agent", ".agent-build",
            "target", "build", "out", "bin", "node_modules", "dist"
    );

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "list_files",
                "List files and directories in the workspace as a tree with sizes. Use it before editing to learn the layout.",
                Schemas.object(Schemas.properties(
                        "path", Schemas.string("Relative directory to list. Defaults to the workspace root."),
                        "depth", Schemas.integer("Maximum recursion depth.", 1, 8),
                        "maxEntries", Schemas.integer("Maximum entries to return.", 1, 1000)
                ))
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String relative = ToolArgs.string(args, "path", ".");
        int depth = ToolArgs.integer(args, "depth", 4, 1, 8);
        int maxEntries = ToolArgs.integer(args, "maxEntries", 400, 1, 1000);
        Workspace workspace = context.workspace();
        Path start = workspace.resolve(relative);
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.error("Path does not exist: " + relative);
        }
        if (Files.isRegularFile(start)) {
            return ToolResult.ok(workspace.relativize(start) + "  (" + workspace.size(start) + "B)");
        }
        if (!Files.isDirectory(start)) {
            return ToolResult.error("Not a directory: " + relative);
        }
        if (start.equals(workspace.root())) {
            return ToolResult.ok(workspace.tree(maxEntries, depth));
        }

        try (Stream<Path> stream = Files.walk(start, depth)) {
            List<Path> entries = stream
                    .filter(path -> !shouldSkip(path, workspace.root()))
                    .limit(maxEntries + 1L)
                    .sorted(Comparator.comparing(workspace::relativize))
                    .toList();
            StringBuilder out = new StringBuilder();
            int count = 0;
            for (Path entry : entries) {
                if (count++ >= maxEntries) {
                    out.append("... (tree truncated)\n");
                    break;
                }
                boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                out.append(directory ? "[d] " : "    ")
                        .append(workspace.relativize(entry));
                if (!directory) {
                    out.append("  (").append(workspace.size(entry)).append("B)");
                }
                out.append('\n');
            }
            return ToolResult.ok(out.toString().stripTrailing());
        } catch (IOException e) {
            return ToolResult.error("Cannot list " + relative + ": " + e.getMessage());
        }
    }

    private static boolean shouldSkip(Path path, Path root) {
        if (path.equals(root)) {
            return false;
        }
        for (Path part : root.relativize(path)) {
            String name = part.toString();
            if (SKIP.contains(name) || name.startsWith(".agent-")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean concurrencySafe() {
        return true;
    }
}
