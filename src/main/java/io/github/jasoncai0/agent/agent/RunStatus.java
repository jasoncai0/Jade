package io.github.jasoncai0.agent.agent;

public enum RunStatus {
    QUEUED,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    MAX_STEPS
}
