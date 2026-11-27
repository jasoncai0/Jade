package io.github.jasoncai0.agent.memory.admission;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable candidate memory awaiting explicit user approval before promotion to Mem0. */
public record PendingMemoryItem(
        String id,
        String projectId,
        String projectName,
        String scope,
        String kind,
        String statement,
        double confidence,
        String reason,
        String evidenceSource,
        int evidenceMessageIndex,
        String evidenceQuote,
        String supersedes,
        String sourceRunId,
        String status,
        String memoryId,
        long createdAtMs,
        long updatedAtMs
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("projectId", projectId);
        map.put("projectName", projectName);
        map.put("scope", scope);
        map.put("kind", kind);
        map.put("statement", statement);
        map.put("confidence", confidence);
        map.put("reason", reason);
        map.put("evidenceSource", evidenceSource);
        map.put("evidenceMessageIndex", evidenceMessageIndex);
        map.put("evidenceQuote", evidenceQuote);
        map.put("supersedes", supersedes == null ? "" : supersedes);
        map.put("sourceRunId", sourceRunId);
        map.put("status", status);
        map.put("memoryId", memoryId == null ? "" : memoryId);
        map.put("createdAtMs", createdAtMs);
        map.put("updatedAtMs", updatedAtMs);
        return map;
    }
}
