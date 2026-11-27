package io.github.jasoncai0.agent.memory.admission;

import java.util.List;

/** Evaluates a completed turn and proposes zero or more durable memory candidates. */
public interface MemoryAdmission {
    AdmissionDecision evaluate(AdmissionContext context);

    default List<AdmissionDecision> evaluateAll(AdmissionContext context) {
        AdmissionDecision single = evaluate(context);
        return single == null ? List.of() : List.of(single);
    }
}
