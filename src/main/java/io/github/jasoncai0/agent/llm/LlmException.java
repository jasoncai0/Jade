package io.github.jasoncai0.agent.llm;

public class LlmException extends RuntimeException {
    private final int statusCode;

    public LlmException(String message) {
        this(message, 0, null);
    }

    public LlmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
