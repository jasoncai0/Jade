package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.Usage;

import java.util.List;

/** Result of the independent goal review that runs before a task is reported done. */
public record GoalReview(boolean aligned, List<String> missing, List<String> issues,
                         Usage usage, String note) {
    public GoalReview {
        missing = missing == null ? List.of() : List.copyOf(missing);
        issues = issues == null ? List.of() : List.copyOf(issues);
        usage = usage == null ? Usage.ZERO : usage;
        note = note == null ? "" : note;
    }

    public static GoalReview allow(Usage usage) {
        return new GoalReview(true, List.of(), List.of(), usage, "");
    }

    public static GoalReview reject(List<String> missing, List<String> issues) {
        return new GoalReview(false, missing, issues, Usage.ZERO, "");
    }
}
