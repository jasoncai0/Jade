package io.github.jasoncai0.agent.config;

import io.github.jasoncai0.agent.permission.PermissionMode;

public record AgentSettings(
        int maxSteps,
        int maxToolCallsPerStep,
        int contextCharBudget,
        int commandTimeoutSeconds,
        boolean autoVerify,
        PermissionMode permissionMode
) {
    public static final int HARD_MAX_STEPS = 30;
    public static final int HARD_MAX_TOOL_CALLS_PER_STEP = 20;

    /** Compatibility constructor for tests and embedders: no interactive approver, so bypass. */
    public AgentSettings(int maxSteps, int maxToolCallsPerStep, int contextCharBudget,
                         int commandTimeoutSeconds, boolean autoVerify) {
        this(maxSteps, maxToolCallsPerStep, contextCharBudget, commandTimeoutSeconds, autoVerify,
                PermissionMode.BYPASS);
    }

    public AgentSettings {
        if (maxSteps < 1 || maxSteps > HARD_MAX_STEPS) {
            throw new IllegalArgumentException("AGENT_MAX_STEPS must be between 1 and " + HARD_MAX_STEPS);
        }
        if (maxToolCallsPerStep < 1 || maxToolCallsPerStep > HARD_MAX_TOOL_CALLS_PER_STEP) {
            throw new IllegalArgumentException(
                    "AGENT_MAX_TOOL_CALLS_PER_STEP must be between 1 and " + HARD_MAX_TOOL_CALLS_PER_STEP);
        }
        if (contextCharBudget < 20_000) {
            throw new IllegalArgumentException("AGENT_CONTEXT_CHAR_BUDGET must be at least 20000");
        }
        if (commandTimeoutSeconds < 1 || commandTimeoutSeconds > 600) {
            throw new IllegalArgumentException("AGENT_COMMAND_TIMEOUT_SECONDS must be between 1 and 600");
        }
        if (permissionMode == null) {
            throw new IllegalArgumentException("permissionMode is required");
        }
    }
}
