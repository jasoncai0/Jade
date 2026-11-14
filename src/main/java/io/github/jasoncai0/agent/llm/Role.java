package io.github.jasoncai0.agent.llm;

public enum Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL;

    public String wireName() {
        return name().toLowerCase();
    }
}
