package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.memory.MemoryCandidate;
import io.github.jasoncai0.agent.memory.MemoryPolicy;

import java.util.ArrayList;
import java.util.List;

/** Deterministic keyword baseline wrapping {@link MemoryPolicy#candidateFromUserMessage}. */
public final class HeuristicAdmission implements MemoryAdmission {
    @Override
    public AdmissionDecision evaluate(AdmissionContext context) {
        List<AdmissionDecision> all = evaluateAll(context);
        return all.isEmpty() ? AdmissionDecision.skip("no_heuristic_match") : all.get(0);
    }

    @Override
    public List<AdmissionDecision> evaluateAll(AdmissionContext context) {
        List<String> messages = context.userMessages();
        List<AdmissionDecision> decisions = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String msg = messages.get(i);
            if (DeterministicAdmissionFilter.isDeferredPhraseOnly(msg)) {
                continue;
            }
            MemoryCandidate candidate = MemoryPolicy.candidateFromUserMessage(msg, context.scope()).orElse(null);
            if (candidate == null) {
                continue;
            }
            String quote = msg.strip();
            if (quote.length() > 240) {
                quote = quote.substring(0, 240);
            }
            decisions.add(new AdmissionDecision(
                    true,
                    candidate.kind(),
                    candidate.global() ? "global" : "project",
                    candidate.text(),
                    List.of(new Evidence("user", i, quote)),
                    candidate.confidence(),
                    "matched deterministic preference/project_fact keywords",
                    ""
            ));
        }
        return decisions.isEmpty() ? List.of(AdmissionDecision.skip("no_heuristic_match")) : decisions;
    }
}
