package io.github.jasoncai0.agent.memory;

import java.util.Map;

/** One recalled memory. Text may be model-extracted and is therefore untrusted input. */
public record MemoryItem(
        String id,
        String text,
        double score,
        String kind,
        String scope,
        String projectId,
        String source,
        String createdAt,
        Map<String, Object> metadata
) {
    public MemoryItem {
        id = id == null ? "" : id.trim();
        text = text == null ? "" : text.strip();
        if (kind == null || kind.isBlank()) kind = "fact";
        if (scope == null || scope.isBlank()) scope = "project";
        if (projectId == null) projectId = "";
        if (source == null) source = "";
        if (createdAt == null) createdAt = "";
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean global() {
        return "global".equals(scope) || MemoryScope.GLOBAL_PROJECT.equals(projectId);
    }
}
