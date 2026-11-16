package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorSummaryTest {
    private final ContextCompactor compactor = new ContextCompactor();

    @Test
    void summarizesRemovedGroupsAndPinsLatestUserMessage() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system prompt"));
        messages.add(ChatMessage.user("original task"));
        for (int i = 0; i < 9; i++) {
            String id = "call-" + i;
            messages.add(new ChatMessage(Role.ASSISTANT, "step " + i, null,
                    List.of(new ToolCall(id, "read_file", "{\"path\":\"src/Main.java\"}")), null));
            messages.add(ChatMessage.tool(id, "x".repeat(900)));
        }
        messages.add(ChatMessage.user("latest constraint: change output to v2"));
        messages.add(ChatMessage.assistant("I will apply that change."));

        AtomicReference<String> seenTask = new AtomicReference<>();
        AtomicInteger seenMessages = new AtomicInteger();
        ContextSummarizer summarizer = (task, removed, maxTokens) -> {
            seenTask.set(task);
            seenMessages.set(removed.size());
            return new ContextSummarizer.Summary("SUMMARY: Main.java exists; output change pending.", new Usage(10, 20, 30));
        };

        ContextCompactor.Result result = compactor.compact(messages, 5_000, 0, summarizer);

        assertTrue(result.compacted());
        assertTrue(result.summarized());
        assertEquals("original task", seenTask.get());
        assertTrue(seenMessages.get() > 0);
        assertEquals("original task", result.messages().get(1).content());
        assertEquals(Role.SYSTEM, result.messages().get(2).role());
        assertTrue(result.messages().get(2).content().contains("SUMMARY: Main.java"));
        assertTrue(result.messages().stream().anyMatch(m -> "latest constraint: change output to v2".equals(m.content())));
        assertEquals(30, result.summaryUsage().totalTokens());
        assertNoOrphanTools(result.messages());
    }

    @Test
    void microCompactsLargeToolOutputEvenWhenNoGroupIsRemoved() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("task"),
                new ChatMessage(Role.ASSISTANT, "read it", null,
                        List.of(new ToolCall("call-1", "read_file", "{\"path\":\"big.txt\"}")), null),
                ChatMessage.tool("call-1", "A".repeat(10_000)),
                ChatMessage.assistant("done"));

        ContextCompactor.Result result = compactor.compact(messages, 1_000_000, 100, null);

        assertTrue(result.compacted());
        assertFalse(result.summarized());
        assertEquals(1, result.microCompacted());
        assertEquals(0, result.removed());
        assertTrue(result.afterTokens() < result.beforeTokens());
        assertTrue(result.messages().stream()
                .anyMatch(m -> m.role() == Role.TOOL && m.content().contains("tool output micro-compacted")));
        assertNoOrphanTools(result.messages());
    }

    @Test
    void fallsBackToNoteWhenSummaryFails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("task"));
        for (int i = 0; i < 8; i++) {
            String id = "call-" + i;
            messages.add(new ChatMessage(Role.ASSISTANT, "step " + i, null,
                    List.of(new ToolCall(id, "read_file", "{\"path\":\"big.txt\"}")), null));
            messages.add(ChatMessage.tool(id, "x".repeat(900)));
        }

        ContextCompactor.Result result = compactor.compact(messages, 5_000, 0,
                (task, removed, maxTokens) -> {
                    throw new IllegalStateException("summary service unavailable");
                });

        assertTrue(result.compacted());
        assertFalse(result.summarized());
        assertTrue(result.messages().get(2).content().contains("Context compacted"));
        assertNoOrphanTools(result.messages());
    }

    private static void assertNoOrphanTools(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.role() != Role.TOOL) {
                continue;
            }
            boolean matched = false;
            for (int j = i - 1; j >= 0; j--) {
                ChatMessage previous = messages.get(j);
                if (previous.role() == Role.ASSISTANT) {
                    matched = previous.toolCalls().stream()
                            .anyMatch(call -> call.id().equals(message.toolCallId()));
                    break;
                }
            }
            assertTrue(matched, "orphan tool result: " + message.toolCallId());
        }
    }
}
