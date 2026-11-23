package io.github.jasoncai0.agent.memory;

/** Stable scoping keys sent to Mem0. */
public record MemoryScope(
        String userId,
        String agentId,
        String projectId,
        String projectName,
        String sessionId
) {
    public static final String GLOBAL_PROJECT = "global";

    public MemoryScope {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId is required");
        projectName = projectName == null ? "" : projectName.trim();
        sessionId = sessionId == null ? "" : sessionId.trim();
    }

    public boolean isGlobalProject() {
        return GLOBAL_PROJECT.equals(projectId);
    }
}
