package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.memory.MemoryPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stage 1 & 2 deterministic pre-filter: bounds window to 4 user messages / 6,000 chars,
 * blocks secrets before any LLM call, and strips bulky code fences from judge prompts.
 */
public final class DeterministicAdmissionFilter {
    public static final int MAX_USER_MESSAGES = 4;
    public static final int MAX_WINDOW_CHARS = 6_000;
    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");
    private static final List<String> DEFERRED_FALSE_POSITIVES = List.of(
            "以后再说", "以后再看", "以后再改", "如果以后", "假如以后", "maybe later", "for later"
    );

    private DeterministicAdmissionFilter() {
    }

    public record FilteredWindow(
            boolean allowed,
            String reasonCode,
            List<String> rawUserMessages,
            List<String> promptUserMessages,
            int inputChars
    ) {
    }

    public static FilteredWindow filter(AdmissionContext context) {
        List<String> source = context.userMessages();
        if (source.isEmpty()) {
            return new FilteredWindow(false, "empty_turn", List.of(), List.of(), 0);
        }
        int start = Math.max(0, source.size() - MAX_USER_MESSAGES);
        List<String> rawWindow = new ArrayList<>();
        List<String> promptWindow = new ArrayList<>();
        int totalChars = 0;

        for (int i = start; i < source.size(); i++) {
            String raw = source.get(i) == null ? "" : source.get(i).strip();
            if (raw.isEmpty()) {
                continue;
            }
            if (MemoryPolicy.isSensitive(raw) || looksLikeEnvDump(raw)) {
                return new FilteredWindow(false, "sensitive_filtered", List.of(), List.of(), raw.length());
            }
            String promptClean = stripCodeFences(raw);
            if (totalChars + promptClean.length() > MAX_WINDOW_CHARS) {
                int remaining = MAX_WINDOW_CHARS - totalChars;
                if (remaining <= 0) {
                    break;
                }
                promptClean = promptClean.substring(0, remaining);
            }
            rawWindow.add(raw);
            promptWindow.add(promptClean);
            totalChars += promptClean.length();
        }
        if (rawWindow.isEmpty()) {
            return new FilteredWindow(false, "empty_after_filter", List.of(), List.of(), 0);
        }
        boolean anyMeaningful = rawWindow.stream().anyMatch(msg -> msg.length() >= 8 && !isDeferredPhraseOnly(msg));
        if (!anyMeaningful) {
            return new FilteredWindow(false, "noise_or_deferred", rawWindow, promptWindow, totalChars);
        }
        return new FilteredWindow(true, "ok", List.copyOf(rawWindow), List.copyOf(promptWindow), totalChars);
    }

    public static boolean isDeferredPhraseOnly(String text) {
        if (text == null) return true;
        String lower = text.strip().toLowerCase(Locale.ROOT);
        if (lower.length() < 8) return true;
        for (String phrase : DEFERRED_FALSE_POSITIVES) {
            if (lower.contains(phrase) && lower.length() <= 40) {
                return true;
            }
        }
        return false;
    }

    public static String stripCodeFences(String text) {
        if (text == null || text.isEmpty()) return "";
        return CODE_FENCE.matcher(text).replaceAll("[code block omitted]").strip();
    }

    private static boolean looksLikeEnvDump(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("deepseek_api_key=")
                || lower.contains("openai_api_key=")
                || lower.contains("anthropic_api_key=")
                || lower.contains("jade_mysql_password=")
                || lower.contains("aws_secret_access_key=");
    }
}
