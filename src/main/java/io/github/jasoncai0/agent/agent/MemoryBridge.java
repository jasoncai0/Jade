package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.memory.MemoryContext;
import io.github.jasoncai0.agent.memory.MemoryItem;
import io.github.jasoncai0.agent.memory.MemoryMode;
import io.github.jasoncai0.agent.memory.MemoryPolicy;
import io.github.jasoncai0.agent.memory.MemoryScope;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.MemorySettings;
import io.github.jasoncai0.agent.memory.admission.AdmissionContext;
import io.github.jasoncai0.agent.memory.admission.AdmissionMode;
import io.github.jasoncai0.agent.memory.admission.MemoryAdmissionPipeline;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolates long-term memory recall and v2 write admission from {@link AgentLoop}.
 */
final class MemoryBridge {
    private final MemoryService memory;
    private final MemoryAdmissionPipeline pipeline;

    MemoryBridge(MemoryService memory, LlmClient llm) {
        this(memory, llm, defaultLegacyPipeline(memory, llm));
    }

    MemoryBridge(MemoryService memory, LlmClient llm, MemoryAdmissionPipeline pipeline) {
        this.memory = memory;
        this.pipeline = pipeline == null ? defaultLegacyPipeline(memory, llm) : pipeline;
    }

    private static MemoryAdmissionPipeline defaultLegacyPipeline(MemoryService memory, LlmClient llm) {
        MemoryMode mode = memory == null ? MemoryMode.OFF : memory.mode();
        MemorySettings fallback = new MemorySettings(
                mode, MemorySettings.DEFAULT_PROVIDER, MemorySettings.DEFAULT_BASE_URL, "",
                MemorySettings.DEFAULT_USER_ID, MemorySettings.DEFAULT_TOP_K,
                MemorySettings.DEFAULT_RECALL_TIMEOUT_MS, MemorySettings.DEFAULT_WRITE_TIMEOUT_MS,
                true, "", AdmissionMode.HEURISTIC, MemorySettings.DEFAULT_AUTO_THRESHOLD,
                MemorySettings.DEFAULT_REVIEW_THRESHOLD, MemorySettings.DEFAULT_MAX_WRITES_PER_RUN,
                MemorySettings.DEFAULT_ADMISSION_TIMEOUT_MS
        );
        return new MemoryAdmissionPipeline(memory, fallback, llm, null);
    }

    static String buildRecallQuery(String latest, List<ChatMessage> history) {
        StringBuilder query = new StringBuilder(latest == null ? "" : latest.strip());
        int seen = 0;
        for (int i = history.size() - 1; i >= 0 && seen < 2; i--) {
            ChatMessage message = history.get(i);
            if (message.role() == Role.USER
                    && message.content() != null && !message.content().isBlank()) {
                query.append("\n").append(message.content().strip());
                seen++;
            }
        }
        String text = query.toString();
        return text.length() <= 2_000 ? text : text.substring(0, 2_000);
    }

    String recall(String query, Workspace workspace, RunState state, RunEventLog log) {
        MemoryService service = memory;
        if (service == null || !service.health().available() || !service.mode().active()) {
            return "";
        }
        MemoryScope scope = service.scope(workspace.root(), state.id());
        String clean = query == null ? "" : query.strip();
        if (clean.isBlank()) {
            return "";
        }
        long start = System.nanoTime();
        String hash = MemoryContext.queryHash(clean);
        log.emit("memory.recall.started", eventMap(
                "queryHash", hash,
                "queryChars", clean.length(),
                "mode", service.mode().id()
        ));
        try {
            List<MemoryItem> candidates = service.recall(clean, scope, MemoryPolicy.MAX_RECALL);
            List<MemoryItem> selected = MemoryPolicy.select(candidates, scope);
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.emit("memory.recall.completed", eventMap(
                    "queryHash", hash,
                    "candidateCount", candidates.size(),
                    "selectedCount", selected.size(),
                    "latencyMs", latency,
                    "topScore", selected.isEmpty() ? 0.0 : selected.get(0).score(),
                    "hit", !selected.isEmpty()
            ));
            if (service.mode() == MemoryMode.SHADOW || selected.isEmpty()) {
                return "";
            }
            String block = MemoryContext.block(selected);
            long globals = selected.stream().filter(MemoryItem::global).count();
            log.emit("memory.injected", eventMap(
                    "memoryIds", selected.stream().map(MemoryItem::id).toList(),
                    "count", selected.size(),
                    "globalCount", globals,
                    "tokens", MemoryContext.tokenEstimate(block)
            ));
            return block;
        } catch (RuntimeException e) {
            Log.warn("memory.recall.failed", Map.of("runId", state.id(),
                    "error", String.valueOf(e.getMessage())));
            log.emit("memory.recall.failed", eventMap(
                    "queryHash", hash,
                    "reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            ));
            return "";
        }
    }

    void maybeWrite(String task, List<String> turnUserMessages, String finalAnswer,
                    Workspace workspace, RunState state, RunEventLog log) {
        MemoryService service = memory;
        if (service == null || !service.mode().active()) {
            return;
        }
        MemoryScope scope = service.scope(workspace.root(), state.id());
        AdmissionContext context = new AdmissionContext(
                state.id(),
                task,
                turnUserMessages,
                finalAnswer,
                state.verification() == null ? "" : state.verification(),
                state.files(),
                scope
        );
        pipeline.processTurnAsync(context, log);
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
