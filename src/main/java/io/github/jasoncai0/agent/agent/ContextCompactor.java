package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops the oldest complete assistant/tool groups when the transcript exceeds the
 * character budget. It never separates a tool call from its tool result.
 */
public final class ContextCompactor {
    private static final int MIN_GROUPS_KEPT = 3;

    public Result compact(List<ChatMessage> messages, int charBudget) {
        int before = chars(messages);
        if (before <= charBudget || messages.size() <= 2) {
            return new Result(List.copyOf(messages), 0, before, before, false);
        }

        ChatMessage system = messages.get(0);
        ChatMessage firstUser = messages.get(1);
        List<List<ChatMessage>> groups = groups(messages.subList(2, messages.size()));

        int keepFrom = 0;
        int currentChars = chars(List.of(system, firstUser))
                + groups.stream().mapToInt(this::chars).sum()
                + 120;
        while (groups.size() - keepFrom > MIN_GROUPS_KEPT && currentChars > charBudget) {
            currentChars -= chars(groups.get(keepFrom));
            keepFrom++;
        }

        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(system);
        compacted.add(firstUser);
        int removed = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (i < keepFrom) {
                removed += groups.get(i).size();
            } else {
                compacted.addAll(groups.get(i));
            }
        }
        if (currentChars > charBudget) {
            compacted = truncateContents(compacted, charBudget);
        }
        if (removed > 0) {
            compacted.add(2, ChatMessage.system(
                    "[Context compacted: " + removed + " earlier messages were removed to stay inside the "
                            + charBudget + " character budget. The original transcript is in .agent/transcript.json.]"));
        }
        int after = chars(compacted);
        return new Result(List.copyOf(compacted), removed, before, after, removed > 0);
    }

    public int chars(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += 80;
            if (message.content() != null) {
                total += message.content().length();
            }
            for (ToolCall call : message.toolCalls()) {
                total += call.name().length() + call.arguments().length() + 32;
            }
        }
        return total;
    }

    private static List<List<ChatMessage>> groups(List<ChatMessage> messages) {
        List<List<ChatMessage>> groups = new ArrayList<>();
        List<ChatMessage> current = null;
        for (ChatMessage message : messages) {
            if (message.role() == Role.TOOL && current != null && !current.isEmpty()
                    && current.get(current.size() - 1).role() == Role.ASSISTANT
                    && current.get(current.size() - 1).hasToolCalls()) {
                current.add(message);
                continue;
            }
            if (message.role() == Role.TOOL) {
                if (current != null) {
                    current.add(message);
                }
                continue;
            }
            current = new ArrayList<>();
            current.add(message);
            groups.add(current);
        }
        return groups;
    }

    private static List<ChatMessage> truncateContents(List<ChatMessage> messages, int budget) {
        int maxPerMessage = Math.max(1200, Math.min(12_000, budget / Math.max(4, messages.size())));
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            String content = message.content();
            if (content != null && content.length() > maxPerMessage) {
                content = content.substring(0, maxPerMessage)
                        + "\n... [message truncated by the context compactor]";
            }
            if (message.toolCalls().isEmpty()) {
                result.add(new ChatMessage(message.role(), content, message.toolCallId(),
                        List.of(), message.reasoningContent()));
            } else {
                result.add(message);
            }
        }
        return result;
    }

    public record Result(
            List<ChatMessage> messages,
            int removed,
            int beforeChars,
            int afterChars,
            boolean compacted
    ) {
    }
}
