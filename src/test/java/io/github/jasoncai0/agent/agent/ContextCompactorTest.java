package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactorTest {
    private final ContextCompactor compactor = new ContextCompactor();

    @Test
    void keepsCompleteToolGroupsAndReportsRemoval() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system prompt"));
        messages.add(ChatMessage.user("do the task"));
        for (int i = 0; i < 12; i++) {
            String id = "call-" + i;
            messages.add(new ChatMessage(Role.ASSISTANT, "step " + i, null,
                    List.of(new ToolCall(id, "read_file", "{\"path\":\"src/Main.java\"}")), null));
            messages.add(ChatMessage.tool(id, "x".repeat(700)));
        }

        ContextCompactor.Result result = compactor.compact(messages, 5_000);

        assertTrue(result.compacted());
        assertTrue(result.removed() > 0);
        assertTrue(result.afterChars() < result.beforeChars());
        assertEquals("system prompt", result.messages().get(0).content());
        assertEquals("do the task", result.messages().get(1).content());
        assertEquals(Role.SYSTEM, result.messages().get(2).role());
        assertNoOrphanTools(result.messages());
    }

    @Test
    void leavesSmallTranscriptsAlone() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("task"),
                ChatMessage.assistant("done"));

        ContextCompactor.Result result = compactor.compact(messages, 100_000);

        assertFalse(result.compacted());
        assertEquals(0, result.removed());
        assertEquals(messages, result.messages());
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

    @Test
    void overflowCompactionKeepsTaskAndLatestUserAndShrinksToolOutput() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system prompt"));
        messages.add(ChatMessage.user("ORIGINAL TASK"));
        for (int i = 0; i < 20; i++) {
            String id = "call-" + i;
            messages.add(new ChatMessage(Role.ASSISTANT, "assistant " + i, null,
                    List.of(new ToolCall(id, "read_file", "{\"path\":\"src/Main.java\"}")), null));
            messages.add(ChatMessage.tool(id, "H" + "x".repeat(4_990) + "T"));
        }
        messages.add(ChatMessage.user("LATEST USER MESSAGE"));
        messages.add(new ChatMessage(Role.ASSISTANT, "after latest", null,
                List.of(new ToolCall("latest-call", "read_file", "{}")), null));
        messages.add(ChatMessage.tool("latest-call", "H" + "y".repeat(2_990) + "T"));

        ContextCompactor.Result result = compactor.compactForOverflow(messages,
                (task, dropped, maxTokens) -> new ContextSummarizer.Summary("SUMMARY", Usage.ZERO));

        assertTrue(result.compacted());
        assertTrue(result.beforeChars() > result.afterChars());
        assertTrue(result.removed() > 0);
        assertEquals("ORIGINAL TASK", result.messages().get(1).content());
        assertTrue(result.messages().stream()
                .anyMatch(message -> "LATEST USER MESSAGE".equals(message.content())));
        assertTrue(result.messages().stream()
                .anyMatch(message -> message.role() == Role.SYSTEM
                        && message.content().contains("SUMMARY")));
        ChatMessage tool = result.messages().stream()
                .filter(message -> message.role() == Role.TOOL)
                .findFirst().orElseThrow();
        assertTrue(tool.content().length() < 2_000, tool.content());
        assertTrue(tool.content().startsWith("H"));
        assertTrue(tool.content().endsWith("T"));
        assertTrue(tool.content().contains("tool output micro-compacted"));
    }

    @Test
    void overflowCompactionFallsBackToNoteWhenSummaryFails() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("task"));
        for (int i = 0; i < 6; i++) {
            String id = "call-" + i;
            messages.add(new ChatMessage(Role.ASSISTANT, "step " + i, null,
                    List.of(new ToolCall(id, "read_file", "{}")), null));
            messages.add(ChatMessage.tool(id, "x".repeat(3_000)));
        }
        messages.add(ChatMessage.user("last"));

        ContextCompactor.Result result = compactor.compactForOverflow(messages,
                (task, dropped, maxTokens) -> {
                    throw new IllegalStateException("summary unavailable");
                });

        assertTrue(result.compacted());
        assertFalse(result.summarized());
        assertTrue(result.messages().stream()
                .anyMatch(message -> message.role() == Role.SYSTEM
                        && message.content().contains("rejected the request")));
    }

    @Test
    void overflowCompactionTruncatesLargeNonToolMessagesAfterLatestUser() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("task"));
        messages.add(ChatMessage.user("latest"));
        messages.add(ChatMessage.assistant("a".repeat(10_000)));

        ContextCompactor.Result result = compactor.compactForOverflow(messages, null);

        ChatMessage assistant = result.messages().get(result.messages().size() - 1);
        assertEquals(Role.ASSISTANT, assistant.role());
        assertTrue(assistant.content().length() < 4_100, "" + assistant.content().length());
        assertTrue(assistant.content().contains("truncated by overflow recovery"));
        assertEquals(1, result.truncated());
    }
}
