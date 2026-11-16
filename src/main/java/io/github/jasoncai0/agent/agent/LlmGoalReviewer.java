package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ResponseFormat;
import io.github.jasoncai0.agent.llm.Usage;

import java.util.ArrayList;
import java.util.List;

/** LLM-backed, read-only reviewer. It does not use tools and cannot modify the workspace. */
public final class LlmGoalReviewer implements GoalReviewer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmClient llm;

    public LlmGoalReviewer(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public GoalReview review(String task, List<PlanItem> plan, String answer,
                             String verification, List<String> files) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("ORIGINAL TASK:\n").append(task).append("\n\n");
        prompt.append("PLAN:\n");
        if (plan == null || plan.isEmpty()) {
            prompt.append("(no plan)\n");
        } else {
            for (PlanItem item : plan) {
                prompt.append("- [").append(item.status()).append("] ").append(item.step()).append('\n');
            }
        }
        prompt.append("\nFILES IN WORKSPACE:\n");
        if (files == null || files.isEmpty()) {
            prompt.append("(none)\n");
        } else {
            prompt.append(String.join("\n", files)).append('\n');
        }
        prompt.append("\nVERIFICATION: ").append(verification == null ? "(none)" : verification).append('\n');
        prompt.append("\nFINAL ANSWER:\n").append(answer == null ? "" : answer).append('\n');

        ChatRequest request = new ChatRequest(
                List.of(
                        ChatMessage.system("""
                                You are a strict goal auditor for a coding agent. Compare the FINAL ANSWER,
                                plan, files, and verification with the ORIGINAL TASK. Decide whether every
                                requested deliverable and constraint is actually satisfied. Do not accept
                                claims without evidence. If work is missing or the answer drifts from the
                                original task, reject it. Return ONLY JSON:
                                {"aligned":true|false,"missing":["..."],"issues":["..."]}.
                                """),
                        ChatMessage.user(prompt.toString())
                ),
                List.of(), 0.0, 600, ResponseFormat.JSON_OBJECT);
        ChatResult result = llm.chat(request);
        Usage usage = result.usage() == null ? Usage.ZERO : result.usage();
        if (result.content() == null || result.content().isBlank()) {
            throw new IllegalArgumentException("Goal reviewer returned an empty response");
        }
        JsonNode node;
        try {
            node = JSON.readTree(result.content());
        } catch (Exception e) {
            throw new IllegalArgumentException("Goal reviewer returned invalid JSON: " + e.getMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Goal reviewer returned a non-object response");
        }
        List<String> missing = strings(node.path("missing"));
        List<String> issues = strings(node.path("issues"));
        boolean aligned = node.path("aligned").asBoolean(false);
        if (!aligned && missing.isEmpty() && issues.isEmpty()) {
            issues = List.of("The final answer does not satisfy the original task.");
        }
        return new GoalReview(aligned, missing, issues, usage, "");
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (!item.asText("").isBlank()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }
}
