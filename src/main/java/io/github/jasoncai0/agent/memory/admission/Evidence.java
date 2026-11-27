package io.github.jasoncai0.agent.memory.admission;

import java.util.Locale;

/** Verbatim quote grounding a candidate memory to a specific source message. */
public record Evidence(String source, int messageIndex, String quote) {
    public Evidence {
        source = source == null || source.isBlank()
                ? "user"
                : source.trim().toLowerCase(Locale.ROOT);
        quote = quote == null ? "" : quote.strip();
    }
}
