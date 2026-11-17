package io.github.jasoncai0.agent.llm;

import java.util.Locale;

public class LlmException extends RuntimeException {
    private final int statusCode;
    private final String code;

    public LlmException(String message) {
        this(message, 0, null, null);
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, null, cause);
    }

    public LlmException(String message, int statusCode, String code, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Provider error code, for example {@code context_length_exceeded}. */
    public String code() {
        return code;
    }

    /**
     * Best-effort detection of a context-window failure. Providers disagree on
     * the code and wording, so check the stable code first and fall back to the
     * message only when no code is present.
     */
    public boolean isContextOverflow() {
        if (code != null && !code.isBlank()) {
            String normalized = code.toLowerCase(Locale.ROOT);
            if (normalized.equals("context_length_exceeded")
                    || normalized.equals("context_window_exceeded")) {
                return true;
            }
        }
        String message = getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("context window")
                || lower.contains("too many tokens")
                || lower.contains("token limit");
    }
}
