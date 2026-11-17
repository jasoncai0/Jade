package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;

import java.util.ArrayList;
import java.util.List;

/**
 * Token/character-budgeted context compaction.
 *
 * <ol>
 *   <li>Pins the system prompt, the first user task, and the group containing the latest user message.</li>
 *   <li>Micro-compacts large tool outputs (head + tail) before summary.</li>
 *   <li>Drops the oldest complete assistant/tool groups, keeping at least three recent groups.</li>
 *   <li>Summarizes dropped groups with a tool-free LLM call and inserts the summary as pinned memory.</li>
 *   <li>Falls back to a deterministic note, then truncates remaining long content if still over budget.</li>
 * </ol>
 */
public final class ContextCompactor {
    private static final int MIN_GROUPS_KEPT = 3;
    private static final int MICRO_TOOL_CHARS = 6_000;
    private static final int MICRO_HEAD_CHARS = 3_000;
    private static final int MICRO_TAIL_CHARS = 1_000;

    /** Backwards-compatible character-only compaction. */
    public Result compact(List<ChatMessage> messages, int charBudget) {
        return compact(messages, charBudget, 0, null);
    }

    public Result compact(List<ChatMessage> messages, int charBudget, int tokenBudget,
                          ContextSummarizer summarizer) {
        int beforeChars = chars(messages);
        int beforeTokens = tokens(messages);
        if ((charBudget <= 0 && tokenBudget <= 0) || messages.size() <= 2
                || (!over(beforeChars, beforeTokens, charBudget, tokenBudget))) {
            return new Result(List.copyOf(messages), 0, 0, 0,
                    beforeChars, beforeChars, beforeTokens, beforeTokens,
                    false, Usage.ZERO, false);
        }

        ChatMessage system = messages.get(0);
        ChatMessage firstUser = messages.get(1);
        List<List<ChatMessage>> groups = groups(messages.subList(2, messages.size()));
        int latestUserGroup = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).stream().anyMatch(message -> message.role() == Role.USER)) {
                latestUserGroup = i;
            }
        }

        int keepFrom = 0;
        int currentChars = chars(List.of(system, firstUser))
                + groups.stream().mapToInt(this::chars).sum() + 120;
        int currentTokens = tokens(List.of(system, firstUser))
                + groups.stream().mapToInt(this::tokens).sum() + 30;
        while (groups.size() - keepFrom > MIN_GROUPS_KEPT
                && (latestUserGroup < 0 || keepFrom <= latestUserGroup)
                && over(currentChars, currentTokens, charBudget, tokenBudget)) {
            currentChars -= chars(groups.get(keepFrom));
            currentTokens -= tokens(groups.get(keepFrom));
            keepFrom++;
        }

        List<ChatMessage> removed = new ArrayList<>();
        for (int i = 0; i < keepFrom; i++) {
            removed.addAll(groups.get(i));
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = keepFrom; i < groups.size(); i++) {
            kept.addAll(groups.get(i));
        }

        MicroResult removedMicro = microCompact(removed);
        MicroResult keptMicro = microCompact(kept);
        removed = removedMicro.messages();
        kept = keptMicro.messages();

        String summary = null;
        Usage summaryUsage = Usage.ZERO;
        boolean summarized = false;
        if (!removed.isEmpty() && summarizer != null) {
            try {
                ContextSummarizer.Summary result = summarizer.summarize(
                        firstUser.content(), removed, 1_200);
                if (!result.text().isBlank()) {
                    summary = result.text();
                    summaryUsage = result.usage();
                    summarized = true;
                }
            } catch (RuntimeException ignored) {
                // Summary is an optimization; the deterministic note below keeps the run going.
            }
        }

        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(system);
        compacted.add(firstUser);
        if (!removed.isEmpty()) {
            if (summarized) {
                compacted.add(ChatMessage.system("[Context summary of earlier conversation]\n" + summary));
            } else {
                compacted.add(ChatMessage.system(
                        "[Context compacted: " + removed.size()
                                + " earlier messages were removed to stay inside the context budget. "
                                + "The full event stream is in .agent/events.jsonl and MySQL history.]"));
            }
        }
        compacted.addAll(kept);

        int protectedCount = removed.isEmpty() ? 2 : 3;
        Truncation truncation = truncate(compacted, charBudget, tokenBudget, protectedCount);
        compacted = truncation.messages();

        int afterChars = chars(compacted);
        int afterTokens = tokens(compacted);
        boolean compactedFlag = !removed.isEmpty() || removedMicro.count() + keptMicro.count() > 0
                || summarized || truncation.count() > 0;
        return new Result(List.copyOf(compacted), removed.size(),
                removedMicro.count() + keptMicro.count(), truncation.count(),
                beforeChars, afterChars, beforeTokens, afterTokens,
                summarized, summaryUsage, compactedFlag);
    }

    /**
     * Last-resort compaction used after the provider rejects a request for
     * exceeding its context window. Unlike {@link #compact}, this may drop the
     * usual three-group minimum and keeps only the system prompt, the original
     * task, and the latest user group.
     */
    public Result compactForOverflow(List<ChatMessage> messages, ContextSummarizer summarizer) {
        int beforeChars = chars(messages);
        int beforeTokens = tokens(messages);
        if (messages.size() <= 2) {
            return new Result(List.copyOf(messages), 0, 0, 0,
                    beforeChars, beforeChars, beforeTokens, beforeTokens,
                    false, Usage.ZERO, false);
        }

        ChatMessage system = messages.get(0);
        ChatMessage firstUser = messages.get(1);
        List<List<ChatMessage>> groups = groups(messages.subList(2, messages.size()));
        int latestUserGroup = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).stream().anyMatch(message -> message.role() == Role.USER)) {
                latestUserGroup = i;
            }
        }
        int keepFrom = latestUserGroup >= 0 ? latestUserGroup : Math.max(0, groups.size() - 1);

        List<ChatMessage> removed = new ArrayList<>();
        for (int i = 0; i < keepFrom; i++) {
            removed.addAll(groups.get(i));
        }
        List<ChatMessage> kept = new ArrayList<>();
        for (int i = keepFrom; i < groups.size(); i++) {
            kept.addAll(groups.get(i));
        }

        MicroResult removedMicro = microCompact(removed, 2_000, 1_000, 500);
        MicroResult keptMicro = microCompact(kept, 2_000, 1_000, 500);
        removed = removedMicro.messages();
        kept = keptMicro.messages();

        String summary = null;
        Usage summaryUsage = Usage.ZERO;
        boolean summarized = false;
        if (!removed.isEmpty() && summarizer != null) {
            try {
                ContextSummarizer.Summary result = summarizer.summarize(
                        firstUser.content(), removed, 1_200);
                if (!result.text().isBlank()) {
                    summary = result.text();
                    summaryUsage = result.usage();
                    summarized = true;
                }
            } catch (RuntimeException ignored) {
                // Fall back to the deterministic note below.
            }
        }

        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(system);
        compacted.add(firstUser);
        if (!removed.isEmpty()) {
            if (summarized) {
                compacted.add(ChatMessage.system("[Context summary of earlier conversation]\n" + summary));
            } else {
                compacted.add(ChatMessage.system(
                        "[Context compacted after the model rejected the request: " + removed.size()
                                + " earlier messages were removed. The full event stream is in "
                                + ".agent/events.jsonl and MySQL history.]"));
            }
        }
        compacted.addAll(kept);

        int latestUserIndex = -1;
        for (int i = compacted.size() - 1; i >= 2; i--) {
            if (compacted.get(i).role() == Role.USER) {
                latestUserIndex = i;
                break;
            }
        }
        int truncated = 0;
        List<ChatMessage> bounded = new ArrayList<>();
        for (int i = 0; i < compacted.size(); i++) {
            ChatMessage message = compacted.get(i);
            String content = message.content();
            boolean protect = i < 2 || i == latestUserIndex || message.role() == Role.TOOL
                    || !message.toolCalls().isEmpty();
            if (!protect && content != null && content.length() > 4_000) {
                content = content.substring(0, 4_000)
                        + "\n... [message truncated by overflow recovery]";
                truncated++;
            }
            bounded.add(new ChatMessage(message.role(), content, message.toolCallId(),
                    List.of(), message.reasoningContent()));
        }
        compacted = bounded;

        int afterChars = chars(compacted);
        int afterTokens = tokens(compacted);
        boolean compactedFlag = !removed.isEmpty() || removedMicro.count() + keptMicro.count() > 0
                || summarized || truncated > 0;
        return new Result(List.copyOf(compacted), removed.size(),
                removedMicro.count() + keptMicro.count(), truncated,
                beforeChars, afterChars, beforeTokens, afterTokens,
                summarized, summaryUsage, compactedFlag);
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

    public int tokens(List<ChatMessage> messages) {
        return TokenEstimator.estimate(messages);
    }

    private static boolean over(int chars, int tokens, int charBudget, int tokenBudget) {
        return (charBudget > 0 && chars > charBudget)
                || (tokenBudget > 0 && tokens > tokenBudget);
    }

    private static List<List<ChatMessage>> groups(List<ChatMessage> messages) {
        List<List<ChatMessage>> groups = new ArrayList<>();
        List<ChatMessage> current = null;
        for (ChatMessage message : messages) {
            if (message.role() == Role.TOOL && current != null && !current.isEmpty()) {
                current.add(message);
                continue;
            }
            if (message.role() == Role.TOOL) {
                continue;
            }
            current = new ArrayList<>();
            current.add(message);
            groups.add(current);
        }
        return groups;
    }

    private MicroResult microCompact(List<ChatMessage> messages) {
        return microCompact(messages, MICRO_TOOL_CHARS, MICRO_HEAD_CHARS, MICRO_TAIL_CHARS);
    }

    private MicroResult microCompact(List<ChatMessage> messages, int threshold, int head, int tail) {
        List<ChatMessage> result = new ArrayList<>();
        int count = 0;
        for (ChatMessage message : messages) {
            String content = message.content();
            if (message.role() == Role.TOOL && content != null && content.length() > threshold) {
                int omitted = content.length() - head - tail;
                String shortened = content.substring(0, head)
                        + "\n... [tool output micro-compacted: " + omitted
                        + " chars omitted; full output is in .agent/events.jsonl]\n"
                        + content.substring(content.length() - tail);
                result.add(ChatMessage.tool(message.toolCallId(), shortened));
                count++;
            } else {
                result.add(message);
            }
        }
        return new MicroResult(List.copyOf(result), count);
    }

    private Truncation truncate(List<ChatMessage> messages, int charBudget, int tokenBudget,
                                int protectedCount) {
        if (!over(chars(messages), tokens(messages), charBudget, tokenBudget)) {
            return new Truncation(messages, 0);
        }
        int basis = charBudget > 0 ? charBudget : Math.max(4_000, tokenBudget * 4);
        int maxPerMessage = Math.max(1_200, Math.min(12_000,
                basis / Math.max(4, messages.size())));
        List<ChatMessage> result = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            String content = message.content();
            if (i < protectedCount || message.toolCalls().size() > 0) {
                result.add(message);
                continue;
            }
            if (content != null && content.length() > maxPerMessage) {
                content = content.substring(0, maxPerMessage)
                        + "\n... [message truncated by the context compactor]";
                count++;
            }
            result.add(new ChatMessage(message.role(), content, message.toolCallId(),
                    List.of(), message.reasoningContent()));
        }
        return new Truncation(List.copyOf(result), count);
    }

    private record MicroResult(List<ChatMessage> messages, int count) {
    }

    private record Truncation(List<ChatMessage> messages, int count) {
    }

    public record Result(
            List<ChatMessage> messages,
            int removed,
            int microCompacted,
            int truncated,
            int beforeChars,
            int afterChars,
            int beforeTokens,
            int afterTokens,
            boolean summarized,
            Usage summaryUsage,
            boolean compacted
    ) {
    }
}
