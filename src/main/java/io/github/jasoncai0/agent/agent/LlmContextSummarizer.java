package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ResponseFormat;
import io.github.jasoncai0.agent.llm.ToolCall;

import java.util.List;

/** Tool-free LLM call that turns dropped conversation groups into pinned textual memory. */
public final class LlmContextSummarizer implements ContextSummarizer {
    private static final int MAX_TRANSCRIPT_CHARS = 80_000;
    private static final int MAX_MESSAGE_CHARS = 3_000;
    private static final int MAX_ARGS_CHARS = 1_500;

    private final LlmClient llm;

    public LlmContextSummarizer(LlmClient llm) {
        this.llm = llm;
    }

    @Override
    public Summary summarize(String task, List<ChatMessage> messages, int maxOutputTokens) {
        String system = """
                You compact an AI coding agent's conversation history.
                Preserve only facts needed to continue: the original task, explicit constraints, decisions,
                files created/changed, commands run, test/compile results, failures, remaining work, and
                exact paths or values that matter. Do not invent facts. Do not call tools.
                Write terse Markdown bullets, at most 800 words.
                """;
        String prompt = "ORIGINAL TASK:\n" + (task == null ? "" : task)
                + "\n\nCONVERSATION TO COMPACT:\n" + render(messages);
        ChatRequest request = new ChatRequest(
                List.of(ChatMessage.system(system), ChatMessage.user(prompt)),
                List.of(), 0.0, Math.max(256, maxOutputTokens), ResponseFormat.TEXT);
        ChatResult result = llm.chat(request);
        return new Summary(result.content(), result.usage());
    }

    private static String render(List<ChatMessage> messages) {
        StringBuilder out = new StringBuilder();
        for (ChatMessage message : messages) {
            String role = message.role() == null ? "unknown" : message.role().wireName();
            out.append("\n### ").append(role.toUpperCase()).append('\n');
            if (message.content() != null && !message.content().isBlank()) {
                out.append(truncate(message.content(), MAX_MESSAGE_CHARS)).append('\n');
            }
            for (ToolCall call : message.toolCalls()) {
                out.append("tool_call ").append(call.name()).append(' ')
                        .append(truncate(call.arguments(), MAX_ARGS_CHARS)).append('\n');
            }
            if (out.length() >= MAX_TRANSCRIPT_CHARS) {
                out.append("\n[... earlier transcript truncated for the summary request]\n");
                break;
            }
        }
        return out.length() > MAX_TRANSCRIPT_CHARS
                ? out.substring(0, MAX_TRANSCRIPT_CHARS) + "\n[... truncated]\n"
                : out.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value
                : value.substring(0, max) + "\n[... truncated ...]";
    }
}
