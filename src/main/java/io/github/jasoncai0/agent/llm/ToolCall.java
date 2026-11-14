package io.github.jasoncai0.agent.llm;

public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        if (id == null || id.isBlank()) {
            id = "call_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool call name must not be blank");
        }
        if (arguments == null || arguments.isBlank()) {
            arguments = "{}";
        }
    }
}
