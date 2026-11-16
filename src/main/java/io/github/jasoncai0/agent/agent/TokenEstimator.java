package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ToolCall;

import java.util.List;

/**
 * Cheap token approximation: ASCII prose/code is roughly 4 chars/token;
 * non-ASCII characters (e.g. Chinese) are roughly 1 token each.
 * It is intentionally conservative enough for a compaction trigger.
 */
public final class TokenEstimator {
    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 0x7f) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return (ascii + 3) / 4 + nonAscii;
    }

    public static int estimate(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += 4;
            total += estimate(message.content());
            total += estimate(message.reasoningContent());
            for (ToolCall call : message.toolCalls()) {
                total += 4;
                total += estimate(call.name());
                total += estimate(call.arguments());
            }
        }
        return total;
    }
}
