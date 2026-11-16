package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmContextSummarizerTest {
    @Test
    void usesToolFreeTextRequestAndReturnsUsage() {
        CapturingLlm llm = new CapturingLlm();
        LlmContextSummarizer summarizer = new LlmContextSummarizer(llm);

        ContextSummarizer.Summary summary = summarizer.summarize(
                "fix the build", List.of(ChatMessage.user("compiler said cannot find symbol")), 900);

        assertEquals("SUMMARY", summary.text());
        assertEquals(12, summary.usage().totalTokens());
        assertTrue(llm.request.tools().isEmpty());
        assertEquals(io.github.jasoncai0.agent.llm.ResponseFormat.TEXT, llm.request.responseFormat());
        assertTrue(llm.request.messages().get(1).content().contains("fix the build"));
        assertTrue(llm.request.messages().get(1).content().contains("cannot find symbol"));
    }

    private static final class CapturingLlm implements LlmClient {
        private ChatRequest request;

        @Override
        public ChatResult chat(ChatRequest request) {
            this.request = request;
            return new ChatResult("SUMMARY", null, List.of(), "stop", new Usage(8, 4, 12));
        }
    }
}
