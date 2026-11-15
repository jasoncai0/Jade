package io.github.jasoncai0.agent.permission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PermissionRequest(
        String id,
        String runId,
        String tool,
        PermissionKind kind,
        String risk,
        String summary,
        String detail,
        String target,
        List<String> options,
        long createdAt,
        long expiresAt
) {
    public PermissionRequest {
        if (id == null || !id.matches("perm-[a-f0-9]{8}")) {
            throw new IllegalArgumentException("Permission request id must look like perm-<8 hex>");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (tool == null || tool.isBlank()) {
            throw new IllegalArgumentException("tool is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        options = options == null ? List.of("once", "session", "deny") : List.copyOf(options);
        detail = detail == null ? "" : detail;
        target = target == null ? "" : target;
        risk = risk == null ? "medium" : risk;
    }

    public static PermissionRequest create(String runId, String tool, PermissionKind kind,
                                           String risk, String summary, String detail,
                                           String target, long ttlMillis) {
        long now = System.currentTimeMillis();
        return new PermissionRequest(
                "perm-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                runId, tool, kind, risk, summary, detail, target,
                List.of("once", "session", "deny"), now, now + Math.max(1_000, ttlMillis));
    }

    public boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public Map<String, Object> toEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("runId", runId);
        data.put("tool", tool);
        data.put("kind", kind.name());
        data.put("risk", risk);
        data.put("summary", summary);
        data.put("detail", detail);
        data.put("target", target);
        data.put("options", options);
        data.put("createdAt", createdAt);
        data.put("expiresAt", expiresAt);
        return data;
    }

    /** Browser-facing view; deliberately contains no credentials or file bodies. */
    public Map<String, Object> publicView() {
        return toEventData();
    }
}
