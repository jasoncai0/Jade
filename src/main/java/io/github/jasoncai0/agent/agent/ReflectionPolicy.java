package io.github.jasoncai0.agent.agent;

/**
 * Decides when a long-running turn should ask the model to reflect, and when
 * the run should stop because reflection did not change the outcome.
 *
 * <p>The thresholds are deliberately constants instead of hidden magic numbers:
 * they are tuning parameters for review. A run with real progress never reaches
 * them because the caller resets its no-progress counter.</p>
 */
public final class ReflectionPolicy {
    public static final int REFLECT_AFTER = 8;
    public static final int STALL_AFTER = 16;

    private ReflectionPolicy() {
    }

    public static String prompt(int noProgressTurns, int toolCalls) {
        return """
                REFLECTION CHECKPOINT
                You have made no workspace change and produced no new verification
                for %d consecutive tool turns. The last turn made %d tool call(s).
                Stop and reflect before taking another action.

                Call the `reflect` tool now with:
                - diagnosis: what you believe is blocking progress;
                - evidence: concrete command/file evidence, not assumptions;
                - attempts: what you already tried;
                - next_action: one specific action that changes the outcome;
                - should_stop: true if the task cannot be completed without user input.

                Do not repeat the same command shape with different line numbers or
                paths. If you cannot name a different next action, stop and report
                the blocker instead of continuing to inspect.
                """.formatted(noProgressTurns, toolCalls);
    }

    public static String stallMessage(int noProgressTurns) {
        return "Stopped: " + noProgressTurns + " consecutive tool turns made no workspace change "
                + "and produced no new verification. The reflection guard treats this as a stall; "
                + "the workspace and transcript are preserved.";
    }
}
