package io.github.jasoncai0.agent.agent;

import java.util.List;

public record PlanItem(String step, String status) {
    public static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

    public PlanItem {
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("Plan step must not be blank");
        }
        step = step.strip();
        status = status == null || status.isBlank() ? "pending" : status.strip().toLowerCase();
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Plan status must be one of " + STATUSES);
        }
    }
}
