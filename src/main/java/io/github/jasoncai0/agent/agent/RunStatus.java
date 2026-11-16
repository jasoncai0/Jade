package io.github.jasoncai0.agent.agent;

public enum RunStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_INPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    MAX_STEPS,
    LOOP_DETECTED,
    BUDGET_EXCEEDED
}
