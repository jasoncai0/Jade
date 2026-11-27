package io.github.jasoncai0.agent.memory.admission;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Structured candidate memory decision emitted by heuristic or strict-tool LLM judge. */
public record AdmissionDecision(
        boolean write,
        String kind,
        String scope,
        String statement,
        List<Evidence> evidence,
        double confidence,
        String reason,
        String supersedesHint
) {
    public static final Set<String> VALID_KINDS = Set.of(
            "preference", "project_fact", "decision", "pitfall", "command_recipe"
    );
    public static final Set<String> AUTO_WRITE_KINDS = Set.of("preference", "project_fact");
    public static final Set<String> VALID_SCOPES = Set.of("project", "global");

    public AdmissionDecision {
        kind = kind == null || kind.isBlank() ? "project_fact" : kind.trim().toLowerCase(Locale.ROOT);
        scope = scope == null || scope.isBlank() ? "project" : scope.trim().toLowerCase(Locale.ROOT);
        statement = statement == null ? "" : statement.strip();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (Double.isNaN(confidence) || confidence < 0.0) {
            confidence = 0.0;
        } else if (confidence > 1.0) {
            confidence = 1.0;
        }
        reason = reason == null ? "" : reason.strip();
        supersedesHint = supersedesHint == null ? "" : supersedesHint.strip();
    }

    public static AdmissionDecision skip(String reason) {
        return new AdmissionDecision(false, "project_fact", "project", "", List.of(), 0.0, reason, "");
    }

    public boolean validKindAndScope() {
        return VALID_KINDS.contains(kind) && VALID_SCOPES.contains(scope);
    }

    public boolean global() {
        return "global".equals(scope);
    }
}
