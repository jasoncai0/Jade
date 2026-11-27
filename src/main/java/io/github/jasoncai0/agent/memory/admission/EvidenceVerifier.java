package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.memory.MemoryPolicy;

import java.util.List;

/**
 * Enforces verbatim quote grounding (§16.6):
 * - Auto-write requires at least one verbatim substring match in {@code userMessages[messageIndex]}.
 * - Fabricated quotes that do not appear in the claimed source are rejected outright.
 * - Evidence drawn only from {@code assistant} or {@code verification} is capped at pending review.
 */
public final class EvidenceVerifier {
    private static final int MIN_QUOTE_CHARS = 4;

    private EvidenceVerifier() {
    }

    public record Outcome(
            boolean valid,
            boolean hasUserVerbatimQuote,
            String reasonCode,
            Evidence primaryEvidence
    ) {
    }

    public static Outcome verify(AdmissionDecision decision, List<String> rawUserMessages,
                                 String finalAnswer, String verification) {
        if (decision == null || !decision.write()) {
            return new Outcome(false, false, "not_write", null);
        }
        if (!decision.validKindAndScope()) {
            return new Outcome(false, false, "invalid_kind_or_scope", null);
        }
        if (decision.statement().isBlank() || decision.statement().length() > 600) {
            return new Outcome(false, false, "invalid_statement_length", null);
        }
        if (MemoryPolicy.isSensitive(decision.statement())) {
            return new Outcome(false, false, "sensitive_statement", null);
        }
        if (decision.evidence().isEmpty()) {
            return new Outcome(false, false, "missing_evidence", null);
        }

        boolean userQuoteVerified = false;
        Evidence firstVerified = null;

        for (Evidence ev : decision.evidence()) {
            String quote = ev.quote();
            if (quote.length() < MIN_QUOTE_CHARS || MemoryPolicy.isSensitive(quote)) {
                return new Outcome(false, false, "invalid_or_sensitive_quote", null);
            }
            switch (ev.source()) {
                case "user" -> {
                    int idx = ev.messageIndex();
                    if (idx < 0 || idx >= rawUserMessages.size()) {
                        return new Outcome(false, false, "message_index_out_of_bounds", null);
                    }
                    String sourceMsg = rawUserMessages.get(idx);
                    if (sourceMsg == null || !sourceMsg.contains(quote)) {
                        return new Outcome(false, false, "user_quote_mismatch", null);
                    }
                    userQuoteVerified = true;
                    if (firstVerified == null) {
                        firstVerified = ev;
                    }
                }
                case "verification" -> {
                    if (verification == null || !verification.contains(quote)) {
                        return new Outcome(false, false, "verification_quote_mismatch", null);
                    }
                    if (firstVerified == null) {
                        firstVerified = ev;
                    }
                }
                case "assistant" -> {
                    if (finalAnswer == null || !finalAnswer.contains(quote)) {
                        return new Outcome(false, false, "assistant_quote_mismatch", null);
                    }
                    if (firstVerified == null) {
                        firstVerified = ev;
                    }
                }
                default -> {
                    return new Outcome(false, false, "unsupported_evidence_source", null);
                }
            }
        }
        if (firstVerified == null) {
            return new Outcome(false, false, "no_verified_quote", null);
        }
        return new Outcome(true, userQuoteVerified,
                userQuoteVerified ? "verified_user_quote" : "non_user_evidence_pending_only",
                firstVerified);
    }
}
