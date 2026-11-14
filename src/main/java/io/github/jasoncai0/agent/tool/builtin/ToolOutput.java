package io.github.jasoncai0.agent.tool.builtin;

import java.util.List;

final class ToolOutput {
    private ToolOutput() {
    }

    static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n... [truncated " + (value.length() - maxChars) + " chars]";
    }

    static String numbered(String content, int startLine, int maxLines) {
        String[] lines = content.split("\n", -1);
        int start = Math.max(1, Math.min(startLine, Math.max(1, lines.length)));
        int end = Math.min(lines.length, start - 1 + maxLines);
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) {
            out.append(String.format("%5d | %s%n", i, lines[i - 1]));
        }
        if (end < lines.length) {
            out.append("... (").append(lines.length - end).append(" more lines)");
        }
        return out.toString().stripTrailing();
    }

    static String join(List<String> lines) {
        return String.join("\n", lines);
    }
}
