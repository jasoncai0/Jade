package io.github.jasoncai0.agent.llm;

import java.util.List;

public record ChatResult(
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        String finishReason,
        Usage usage
) {
    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.ZERO : usage;
    }
}
