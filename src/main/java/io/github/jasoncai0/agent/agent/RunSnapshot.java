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
        String transcriptPath,
        String parentRunId,
        Long forkSeq
) {
    /** Compatibility constructor for callers that predate session forks. */
    public RunSnapshot(String id, String task, String workspace, String status, String phase,
                       String phaseLabel, int step, int maxSteps, List<PlanItem> plan,
                       List<String> files, Usage usage, String finalAnswer, String error,
                       String verification, long startedAt, long finishedAt, String transcriptPath) {
        this(id, task, workspace, status, phase, phaseLabel, step, maxSteps, plan, files, usage,
                finalAnswer, error, verification, startedAt, finishedAt, transcriptPath, null, null);
    }

    public boolean forked() {
        return parentRunId != null && !parentRunId.isBlank();
    }

    /** Returns a copy carrying fork lineage, used when a saved snapshot predates it. */
    public RunSnapshot withFork(String parent, Long seq) {
        return new RunSnapshot(id, task, workspace, status, phase, phaseLabel, step, maxSteps,
                plan, files, usage, finalAnswer, error, verification, startedAt, finishedAt,
                transcriptPath, parent, seq);
    }

    public boolean terminal() {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "MAX_STEPS", "LOOP_DETECTED", "BUDGET_EXCEEDED" -> true;
            default -> false;
        };
    }
}
