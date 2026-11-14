package io.github.jasoncai0.agent.llm;

import java.util.List;

public record ChatRequest(
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        double temperature,
        int maxTokens,
        ResponseFormat responseFormat
) {
    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Backwards-compatible constructor that leaves response_format unset.
     */
    public ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, double temperature, int maxTokens) {
        this(messages, tools, temperature, maxTokens, null);
    }

    public boolean hasStrictTools() {
        return tools.stream().anyMatch(ToolSpec::strict);
    }
}
