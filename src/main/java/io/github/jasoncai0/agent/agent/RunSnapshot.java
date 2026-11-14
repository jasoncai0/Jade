package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.Usage;

import java.util.List;

public record RunSnapshot(
        String id,
        String task,
        String workspace,
        String status,
        String phase,
        String phaseLabel,
        int step,
        int maxSteps,
        List<PlanItem> plan,
        List<String> files,
        Usage usage,
        String finalAnswer,
        String error,
        String verification,
        long startedAt,
        long finishedAt,
        String transcriptPath
) {
    public boolean terminal() {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "MAX_STEPS" -> true;
            default -> false;
        };
    }
}
