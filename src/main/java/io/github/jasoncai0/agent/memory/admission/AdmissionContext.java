package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.memory.MemoryScope;

import java.util.List;

/** Bounded turn context supplied to the memory admission judge after a successful run. */
public record AdmissionContext(
        String runId,
        String task,
        List<String> userMessages,
        String finalAnswer,
        String verification,
        List<String> files,
        MemoryScope scope
) {
    public AdmissionContext {
        runId = runId == null ? "" : runId.trim();
        task = task == null ? "" : task.strip();
        userMessages = userMessages == null ? List.of() : List.copyOf(userMessages);
        finalAnswer = finalAnswer == null ? "" : finalAnswer.strip();
        verification = verification == null ? "" : verification.strip();
        files = files == null ? List.of() : List.copyOf(files);
        if (scope == null) {
            throw new IllegalArgumentException("MemoryScope is required");
        }
    }
}
