package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.agent.RunEventLog;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.memory.MemoryCandidate;
import io.github.jasoncai0.agent.memory.MemoryMode;
import io.github.jasoncai0.agent.memory.MemoryScope;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.MemorySettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * End-to-end Mem0 v2 admission pipeline (§16.3):
 * Window & secret filter -> Judge (Heuristic / LLM / Hybrid) -> Verbatim EvidenceVerifier
 * -> Dedup/Supersedes & Threshold Policy -> Auto-write (infer=false) or Pending Inbox -> Audit events.
 */
public final class MemoryAdmissionPipeline {
    private final MemoryService memory;
    private final MemorySettings settings;
    private final PendingMemoryStore pendingStore;
    private final HeuristicAdmission heuristic = new HeuristicAdmission();
    private final LlmMemoryAdmission llmAdmission;

    public MemoryAdmissionPipeline(MemoryService memory, MemorySettings settings,
                                   LlmClient llm, PendingMemoryStore pendingStore) {
        this.memory = memory;
        this.settings = settings == null ? MemorySettings.defaults() : settings;
        this.pendingStore = pendingStore == null ? new InMemoryPendingMemoryStore() : pendingStore;
        this.llmAdmission = llm == null ? null : new LlmMemoryAdmission(llm, this.settings.admissionTimeoutMs());
    }

    public PendingMemoryStore pendingStore() {
        return pendingStore;
    }

    public MemorySettings settings() {
        return settings;
    }

    public void processTurnAsync(AdmissionContext context, RunEventLog log) {
        if (memory == null || settings.mode() == MemoryMode.OFF) {
            return;
        }
        boolean shadow = settings.mode() == MemoryMode.SHADOW;
        if (!shadow && !memory.health().available() && settings.admissionMode() == AdmissionMode.OFF) {
            return;
        }
        memory.submitAsync(() -> processTurnSync(context, log));
    }

    public List<MemoryWritePolicy.Resolution> processTurnSync(AdmissionContext context, RunEventLog log) {
        long start = System.nanoTime();
        AdmissionMode admissionMode = settings.admissionMode();
        if (settings.mode() == MemoryMode.OFF) {
            return List.of();
        }
        DeterministicAdmissionFilter.FilteredWindow window = DeterministicAdmissionFilter.filter(context);
        if (!window.allowed()) {
            if ("sensitive_filtered".equals(window.reasonCode()) && log != null) {
                log.emit("memory.admission.completed", eventMap(
                        "runId", context.runId(),
                        "decision", "discard",
                        "reasonCode", window.reasonCode(),
                        "inputChars", window.inputChars(),
                        "latencyMs", elapsedMs(start)
                ));
            }
            return List.of(new MemoryWritePolicy.Resolution(
                    MemoryWritePolicy.Action.DISCARD, window.reasonCode(), ""));
        }

        AdmissionContext bounded = new AdmissionContext(
                context.runId(),
                context.task(),
                window.rawUserMessages(),
                context.finalAnswer(),
                context.verification(),
                context.files(),
                context.scope()
        );

        if (log != null) {
            log.emit("memory.admission.started", eventMap(
                    "runId", context.runId(),
                    "admissionMode", admissionMode.id(),
                    "memoryMode", settings.mode().id(),
                    "inputChars", window.inputChars(),
                    "userMessages", window.rawUserMessages().size()
            ));
        }

        List<AdmissionDecision> rawDecisions;
        boolean usedHeuristicOnly = false;
        try {
            rawDecisions = switch (admissionMode) {
                case OFF, HEURISTIC -> {
                    usedHeuristicOnly = true;
                    yield heuristic.evaluateAll(bounded);
                }
                case LLM -> {
                    if (llmAdmission == null) {
                        throw new IllegalStateException("LLM admission requested without LlmClient");
                    }
                    yield llmAdmission.evaluateAll(bounded);
                }
                case HYBRID -> {
                    if (llmAdmission != null) {
                        yield llmAdmission.evaluateAll(bounded);
                    }
                    usedHeuristicOnly = true;
                    yield heuristic.evaluateAll(bounded);
                }
            };
        } catch (RuntimeException e) {
            if (log != null) {
                log.emit("memory.admission.failed", eventMap(
                        "runId", context.runId(),
                        "reasonCode", "judge_error",
                        "latencyMs", elapsedMs(start),
                        "inputChars", window.inputChars()
                ));
            }
            return List.of(new MemoryWritePolicy.Resolution(
                    MemoryWritePolicy.Action.DISCARD, "judge_error", ""));
        }

        List<AdmissionDecision> writeCandidates = rawDecisions.stream()
                .filter(d -> d != null && d.write())
                .sorted(Comparator.comparingDouble(AdmissionDecision::confidence).reversed())
                .limit(settings.maxWritesPerRun())
                .toList();

        if (writeCandidates.isEmpty()) {
            if (log != null) {
                log.emit("memory.admission.completed", eventMap(
                        "runId", context.runId(),
                        "decision", "skip",
                        "reasonCode", "judge_declined",
                        "latencyMs", elapsedMs(start),
                        "inputChars", window.inputChars(),
                        "evidenceCount", 0
                ));
            }
            return List.of(new MemoryWritePolicy.Resolution(
                    MemoryWritePolicy.Action.DISCARD, "judge_declined", ""));
        }

        long now = System.currentTimeMillis();
        boolean shadow = settings.mode() == MemoryMode.SHADOW;
        List<MemoryWritePolicy.Resolution> resolutions = new ArrayList<>();

        for (AdmissionDecision decision : writeCandidates) {
            EvidenceVerifier.Outcome verified = EvidenceVerifier.verify(
                    decision, bounded.userMessages(), bounded.finalAnswer(), bounded.verification());
            if (!verified.valid()) {
                if (log != null) {
                    log.emit("memory.admission.failed", eventMap(
                            "runId", context.runId(),
                            "kind", decision.kind(),
                            "scope", decision.scope(),
                            "confidence", decision.confidence(),
                            "reasonCode", verified.reasonCode(),
                            "latencyMs", elapsedMs(start),
                            "inputChars", window.inputChars(),
                            "evidenceCount", decision.evidence().size()
                    ));
                }
                resolutions.add(new MemoryWritePolicy.Resolution(
                        MemoryWritePolicy.Action.DISCARD, verified.reasonCode(), ""));
                continue;
            }

            MemoryWritePolicy.Resolution res;
            if (usedHeuristicOnly) {
                // Legacy heuristic path preserves direct behavior when AGENT_MEMORY_ADMISSION=off|heuristic
                res = new MemoryWritePolicy.Resolution(MemoryWritePolicy.Action.AUTO_WRITE, "heuristic_baseline", "");
            } else {
                res = MemoryWritePolicy.resolve(decision, verified, settings, memory, bounded.scope(), now);
            }
            resolutions.add(res);

            if (shadow) {
                if (log != null) {
                    log.emit("memory.admission.completed", eventMap(
                            "runId", context.runId(),
                            "decision", "shadow_" + res.action().name().toLowerCase(),
                            "kind", decision.kind(),
                            "scope", decision.scope(),
                            "confidence", decision.confidence(),
                            "reasonCode", res.reasonCode(),
                            "latencyMs", elapsedMs(start),
                            "inputChars", window.inputChars(),
                            "evidenceCount", decision.evidence().size()
                    ));
                }
                continue;
            }

            switch (res.action()) {
                case AUTO_WRITE -> {
                    if (!memory.health().available()) {
                        enqueuePending(bounded, decision, verified, res.supersedesId(), now, log, start, window.inputChars());
                    } else {
                        try {
                            MemoryCandidate candidate = new MemoryCandidate(
                                    decision.statement(),
                                    decision.kind(),
                                    "user",
                                    decision.confidence(),
                                    usedHeuristicOnly, // infer=false on hybrid/llm, infer=true only on legacy heuristic
                                    decision.global(),
                                    bounded.runId(),
                                    res.supersedesId()
                            );
                            String memoryId = memory.remember(candidate, bounded.scope());
                            if (!res.supersedesId().isBlank()) {
                                try {
                                    memory.forget(res.supersedesId());
                                } catch (RuntimeException ignored) {
                                }
                            }
                            if (log != null) {
                                log.emit("memory.admission.completed", eventMap(
                                        "runId", context.runId(),
                                        "decision", "auto_write",
                                        "kind", decision.kind(),
                                        "scope", decision.scope(),
                                        "confidence", decision.confidence(),
                                        "reasonCode", res.reasonCode(),
                                        "memoryId", memoryId == null ? "" : memoryId,
                                        "latencyMs", elapsedMs(start),
                                        "inputChars", window.inputChars(),
                                        "evidenceCount", decision.evidence().size()
                                ));
                            }
                        } catch (RuntimeException e) {
                            if (log != null) {
                                log.emit("memory.admission.failed", eventMap(
                                        "runId", context.runId(),
                                        "kind", decision.kind(),
                                        "scope", decision.scope(),
                                        "confidence", decision.confidence(),
                                        "reasonCode", "mem0_write_error",
                                        "latencyMs", elapsedMs(start),
                                        "inputChars", window.inputChars(),
                                        "evidenceCount", decision.evidence().size()
                                ));
                            }
                        }
                    }
                }
                case PENDING -> enqueuePending(bounded, decision, verified, res.supersedesId(),
                        now, log, start, window.inputChars());
                case DISCARD -> {
                    if (log != null) {
                        log.emit("memory.admission.completed", eventMap(
                                "runId", context.runId(),
                                "decision", "discard",
                                "kind", decision.kind(),
                                "scope", decision.scope(),
                                "confidence", decision.confidence(),
                                "reasonCode", res.reasonCode(),
                                "latencyMs", elapsedMs(start),
                                "inputChars", window.inputChars(),
                                "evidenceCount", decision.evidence().size()
                        ));
                    }
                }
            }
        }
        return resolutions;
    }

    public PendingMemoryItem approvePending(String pendingId, MemoryScope fallbackScope) {
        PendingMemoryItem item = pendingStore.find(pendingId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Pending memory not found: " + pendingId));
        if (!"pending".equals(item.status())) {
            throw new IllegalStateException("Pending memory " + pendingId + " is already " + item.status());
        }
        if (memory == null || !memory.health().available()) {
            throw new IllegalStateException("Mem0 service is unavailable; start Mem0 before approving pending memories");
        }
        MemoryScope targetScope = new MemoryScope(
                fallbackScope.userId(),
                fallbackScope.agentId(),
                item.projectId(),
                item.projectName(),
                item.sourceRunId()
        );
        MemoryCandidate candidate = new MemoryCandidate(
                item.statement(),
                item.kind(),
                "user_approved",
                Math.max(item.confidence(), 0.90),
                false, // infer=false: keep exact approved statement
                "global".equals(item.scope()) || MemoryScope.GLOBAL_PROJECT.equals(item.projectId()),
                item.sourceRunId(),
                item.supersedes()
        );
        // Remote Mem0 write happens OUTSIDE any database transaction
        String memoryId = memory.remember(candidate, targetScope);
        if (item.supersedes() != null && !item.supersedes().isBlank()) {
            try {
                memory.forget(item.supersedes());
            } catch (RuntimeException ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (!pendingStore.markApproved(pendingId, memoryId, now)) {
            throw new IllegalStateException("Pending memory " + pendingId + " was already resolved concurrently");
        }
        return pendingStore.find(pendingId).orElse(item);
    }

    public PendingMemoryItem rejectPending(String pendingId) {
        PendingMemoryItem item = pendingStore.find(pendingId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Pending memory not found: " + pendingId));
        if (!"pending".equals(item.status())) {
            throw new IllegalStateException("Pending memory " + pendingId + " is already " + item.status());
        }
        long now = System.currentTimeMillis();
        if (!pendingStore.markRejected(pendingId, now)) {
            throw new IllegalStateException("Pending memory " + pendingId + " was already resolved concurrently");
        }
        return pendingStore.find(pendingId).orElse(item);
    }

    private void enqueuePending(AdmissionContext context, AdmissionDecision decision,
                                EvidenceVerifier.Outcome verified, String supersedesId,
                                long now, RunEventLog log, long startNanos, int inputChars) {
        Evidence ev = verified.primaryEvidence();
        String id = "pmem-" + UUID.randomUUID().toString().substring(0, 10);
        String projectId = decision.global() ? MemoryScope.GLOBAL_PROJECT : context.scope().projectId();
        String projectName = decision.global() ? "global" : context.scope().projectName();
        PendingMemoryItem pending = new PendingMemoryItem(
                id,
                projectId,
                projectName,
                decision.scope(),
                decision.kind(),
                decision.statement(),
                decision.confidence(),
                decision.reason(),
                ev == null ? "user" : ev.source(),
                ev == null ? 0 : ev.messageIndex(),
                ev == null ? "" : ev.quote(),
                supersedesId == null ? "" : supersedesId,
                context.runId(),
                "pending",
                "",
                now,
                now
        );
        pendingStore.enqueue(pending);
        if (log != null) {
            log.emit("memory.admission.pending", eventMap(
                    "runId", context.runId(),
                    "pendingId", id,
                    "decision", "pending",
                    "kind", decision.kind(),
                    "scope", decision.scope(),
                    "confidence", decision.confidence(),
                    "reasonCode", "queued_for_review",
                    "latencyMs", elapsedMs(startNanos),
                    "inputChars", inputChars,
                    "evidenceCount", decision.evidence().size()
            ));
        }
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static Map<String, Object> eventMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }
}
