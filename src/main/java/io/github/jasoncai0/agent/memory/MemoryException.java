package io.github.jasoncai0.agent.memory;

/** A non-fatal memory backend failure. Callers must fall back to normal work. */
public class MemoryException extends RuntimeException {
    public MemoryException(String message) {
        super(message);
    }

    public MemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
