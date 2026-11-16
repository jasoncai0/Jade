package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.Usage;

import java.util.List;

/** Produces a compact, model-authored memory of conversation groups that are being dropped. */
public interface ContextSummarizer {
    Summary summarize(String task, List<ChatMessage> messages, int maxOutputTokens);

    record Summary(String text, Usage usage) {
        public Summary {
            text = text == null ? "" : text.trim();
            usage = usage == null ? Usage.ZERO : usage;
        }
    }
}
