package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.memory.MemoryItem;
import io.github.jasoncai0.agent.memory.MemoryPolicy;
import io.github.jasoncai0.agent.memory.MemoryScope;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.MemorySettings;

import java.util.List;
import java.util.Locale;

/**
 * Applies confidence thresholds, kind gates, deduplication, and supersedes detection (§16.7–16.8):
 * - Auto-write: confidence >= autoThreshold AND verified user quote AND kind in {preference, project_fact}.
 * - Pending inbox: reviewThreshold <= confidence < autoThreshold, OR kind in {decision, pitfall, command_recipe},
 *   OR non-user (verification/assistant) evidence.
 * - Discard: confidence < reviewThreshold or duplicate (score >= 0.85 or normalized statement match).
 */
public final class MemoryWritePolicy {
    public enum Action {
        AUTO_WRITE, PENDING, DISCARD
    }

    public record Resolution(
            Action action,
            String reasonCode,
            String supersedesId
    ) {
    }

    private MemoryWritePolicy() {
    }

    public static Resolution resolve(AdmissionDecision decision,
                                     EvidenceVerifier.Outcome verification,
                                     MemorySettings settings,
                                     MemoryService memory,
                                     MemoryScope scope,
                                     long nowMillis) {
        if (decision == null || !decision.write()) {
            return new Resolution(Action.DISCARD, "judge_skip", "");
        }
        if (verification == null || !verification.valid()) {
            return new Resolution(Action.DISCARD,
                    verification == null ? "invalid_evidence" : verification.reasonCode(), "");
        }
        double conf = decision.confidence();
        if (conf < settings.reviewThreshold()) {
            return new Resolution(Action.DISCARD, "below_review_threshold", "");
        }

        String supersedesId = "";
        if (memory != null && memory.health().available()) {
            try {
                List<MemoryItem> existing = memory.recall(decision.statement(), scope, 5);
                String normStatement = normalize(decision.statement());
                for (MemoryItem item : existing) {
                    if (item == null) continue;
                    if (!MemoryPolicy.isActive(item, nowMillis)) {
                        // Purge expired memory lazily before evaluating duplicates/conflicts
                        if (!item.id().isBlank()) {
                            try {
                                memory.forget(item.id());
                            } catch (RuntimeException ignored) {
                            }
                        }
                        continue;
                    }
                    boolean sameScope = decision.global()
                            ? item.global()
                            : scope.projectId().equals(item.projectId());
                    if (!sameScope) continue;
                    if (normalize(item.text()).equals(normStatement)
                            || (decision.kind().equalsIgnoreCase(item.kind()) && item.score() >= 0.90)) {
                        return new Resolution(Action.DISCARD, "duplicate_memory", "");
                    }
                    if (decision.kind().equalsIgnoreCase(item.kind())) {
                        if (!decision.supersedesHint().isBlank()
                                && (decision.supersedesHint().equals(item.id())
                                || item.text().toLowerCase(Locale.ROOT)
                                        .contains(decision.supersedesHint().toLowerCase(Locale.ROOT)))) {
                            supersedesId = item.id();
                        } else if (item.score() >= 0.72 && item.score() < 0.90 && supersedesId.isEmpty()) {
                            supersedesId = item.id();
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Dedup lookup is best-effort; never fail admission if search times out.
            }
        }

        boolean eligibleForAuto = conf >= settings.autoThreshold()
                && verification.hasUserVerbatimQuote()
                && AdmissionDecision.AUTO_WRITE_KINDS.contains(decision.kind());
        if (eligibleForAuto) {
            return new Resolution(Action.AUTO_WRITE, "auto_approved", supersedesId);
        }
        String pendingReason = !verification.hasUserVerbatimQuote()
                ? "non_user_evidence_requires_review"
                : !AdmissionDecision.AUTO_WRITE_KINDS.contains(decision.kind())
                        ? "kind_requires_review"
                        : "medium_confidence_review";
        return new Resolution(Action.PENDING, pendingReason, supersedesId);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s。.,!?;:]+", " ");
    }
}
