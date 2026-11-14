package io.github.jasoncai0.agent.llm;

import java.util.List;

public record ChatMessage(
        Role role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls,
        String reasoningContent
) {
    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, null, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, null, List.of(), null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, null, List.of(), null);
    }

    public static ChatMessage assistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, null, toolCalls, reasoningContent);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, toolCallId, List.of(), null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
