package io.github.jasoncai0.agent.config;

import io.github.jasoncai0.agent.permission.PermissionMode;

public record AgentSettings(
        int maxSteps,
        int maxToolCallsPerStep,
        int contextCharBudget,
        int commandTimeoutSeconds,
        boolean autoVerify,
        PermissionMode permissionMode,
        long tokenBudget,
        boolean goalMode,
        int contextTokenBudget,
        boolean overflowRecovery
) {
    /** 0 means unlimited; positive values remain an optional explicit cap. */
    public static final int HARD_MAX_STEPS = 10_000;
    public static final int HARD_MAX_TOOL_CALLS_PER_STEP = 20;
    public static final long DEFAULT_TOKEN_BUDGET = 0L;
    public static final int DEFAULT_CONTEXT_TOKEN_BUDGET = 30_000;
    public static final boolean DEFAULT_OVERFLOW_RECOVERY = true;

    /** Compatibility constructor for tests and embedders: no interactive approver, no token budget. */
    public AgentSettings(int maxSteps, int maxToolCallsPerStep, int contextCharBudget,
                         int commandTimeoutSeconds, boolean autoVerify) {
        this(maxSteps, maxToolCallsPerStep, contextCharBudget, commandTimeoutSeconds, autoVerify,
                PermissionMode.BYPASS, 0, false, 0, DEFAULT_OVERFLOW_RECOVERY);
    }

    /** Compatibility constructor for permission-aware tests; no token budget. */
    public AgentSettings(int maxSteps, int maxToolCallsPerStep, int contextCharBudget,
                         int commandTimeoutSeconds, boolean autoVerify, PermissionMode permissionMode) {
        this(maxSteps, maxToolCallsPerStep, contextCharBudget, commandTimeoutSeconds, autoVerify,
                permissionMode, 0, false, 0, DEFAULT_OVERFLOW_RECOVERY);
    }

    /** Compatibility constructor for token-budget tests; goal mode is opt-in. */
    public AgentSettings(int maxSteps, int maxToolCallsPerStep, int contextCharBudget,
                         int commandTimeoutSeconds, boolean autoVerify,
                         PermissionMode permissionMode, long tokenBudget) {
        this(maxSteps, maxToolCallsPerStep, contextCharBudget, commandTimeoutSeconds, autoVerify,
                permissionMode, tokenBudget, false, 0, DEFAULT_OVERFLOW_RECOVERY);
    }

    /** Compatibility constructor for goal-mode tests; context token budget is opt-in. */
    public AgentSettings(int maxSteps, int maxToolCallsPerStep, int contextCharBudget,
                         int commandTimeoutSeconds, boolean autoVerify,
                         PermissionMode permissionMode, long tokenBudget, boolean goalMode) {
        this(maxSteps, maxToolCallsPerStep, contextCharBudget, commandTimeoutSeconds, autoVerify,
                permissionMode, tokenBudget, goalMode, 0, DEFAULT_OVERFLOW_RECOVERY);
    }

    /** Compatibility constructor for callers that predate overflow recovery. */
    public AgentSettings(int maxSteps, int maxToolCallsPerStep, int contextCharBudget,
                         int commandTimeoutSeconds, boolean autoVerify,
                         PermissionMode permissionMode, long tokenBudget, boolean goalMode,
                         int contextTokenBudget) {
        this(maxSteps, maxToolCallsPerStep, contextCharBudget, commandTimeoutSeconds, autoVerify,
                permissionMode, tokenBudget, goalMode, contextTokenBudget, DEFAULT_OVERFLOW_RECOVERY);
    }

    public AgentSettings {
        if (maxSteps < 0 || maxSteps > HARD_MAX_STEPS) {
            throw new IllegalArgumentException("AGENT_MAX_STEPS must be between 0 and " + HARD_MAX_STEPS
                    + " (0 = unlimited)");
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
        if (tokenBudget < 0) {
            throw new IllegalArgumentException("AGENT_TOKEN_BUDGET must be >= 0 (0 = unlimited)");
        }
        if (contextTokenBudget < 0 || contextTokenBudget > 200_000) {
            throw new IllegalArgumentException("AGENT_CONTEXT_TOKEN_BUDGET must be between 0 and 200000 (0 = disabled)");
        }
    }
}
