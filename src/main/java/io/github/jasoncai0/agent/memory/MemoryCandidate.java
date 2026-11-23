package io.github.jasoncai0.agent.memory;

/** A candidate extracted locally before it is sent to Mem0. */
public record MemoryCandidate(
        String text,
        String kind,
        String source,
        double confidence,
        boolean infer,
        boolean global,
        String sessionId
) {
    public MemoryCandidate {
        text = text == null ? "" : text.strip();
        if (text.isBlank()) throw new IllegalArgumentException("memory text is required");
        if (kind == null || kind.isBlank()) kind = "fact";
        if (source == null || source.isBlank()) source = "user";
        if (confidence < 0 || confidence > 1) confidence = 0.5;
        if (sessionId == null) sessionId = "";
    }
}
