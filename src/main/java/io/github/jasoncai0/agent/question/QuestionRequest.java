package io.github.jasoncai0.agent.question;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record QuestionRequest(
        String id,
        String runId,
        String question,
        String detail,
        QuestionMode mode,
        List<QuestionOption> options,
        long createdAt,
        long expiresAt
) {
    public QuestionRequest {
        if (id == null || !id.matches("ask-[a-f0-9]{8}")) {
            throw new IllegalArgumentException("Invalid question id");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        mode = mode == null ? QuestionMode.SINGLE : mode;
        options = options == null ? List.of() : List.copyOf(options);
        if (mode != QuestionMode.TEXT && options.isEmpty()) {
            throw new IllegalArgumentException("options are required for single/multiple questions");
        }
    }

    public static QuestionRequest create(String runId, String question, String detail,
                                         QuestionMode mode, List<QuestionOption> options,
                                         long ttlMillis) {
        long now = System.currentTimeMillis();
        return new QuestionRequest(
                "ask-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                runId, question == null ? "" : question.strip(),
                detail == null ? "" : detail.strip(), mode, options,
                now, now + Math.max(1, ttlMillis));
    }

    public boolean expired() {
        return System.currentTimeMillis() >= expiresAt;
    }

    public Map<String, Object> toEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("runId", runId);
        data.put("question", question);
        data.put("detail", detail);
        data.put("mode", mode.id());
        data.put("options", options);
        data.put("createdAt", createdAt);
        data.put("expiresAt", expiresAt);
        return data;
    }

    public Map<String, Object> publicView() {
        return toEventData();
    }
}
