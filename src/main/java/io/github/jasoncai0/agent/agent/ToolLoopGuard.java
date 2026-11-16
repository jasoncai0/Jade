package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ToolCall;

/**
 * Stops the most common agent loop: the same tool call repeated over and over
 * with identical arguments. The guard is intentionally conservative; it does
 * not try to judge intent or replace the per-session token budget.
 */
final class ToolLoopGuard {
    static final int REPEAT_LIMIT = 4;

    private String lastSignature;
    private int repeats;

    /** Returns a human-readable reason when the repeat limit is crossed. */
    String observe(ToolCall call) {
        String signature = call.name() + "\u0000" + canonical(call.arguments());
        if (signature.equals(lastSignature)) {
            repeats++;
        } else {
            lastSignature = signature;
            repeats = 1;
        }
        if (repeats >= REPEAT_LIMIT) {
            return "The same tool call (" + call.name() + ") was repeated " + repeats
                    + " times with identical arguments. The loop guard stopped the run; "
                    + "inspect the trace and send a follow-up with a different strategy.";
        }
        return null;
    }

    void reset() {
        lastSignature = null;
        repeats = 0;
    }

    private static String canonical(String arguments) {
        if (arguments == null) {
            return "";
        }
        return arguments.replaceAll("\\s+", "");
    }
}
