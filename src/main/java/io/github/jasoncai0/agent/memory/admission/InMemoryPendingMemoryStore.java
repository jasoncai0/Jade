package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.memory.MemoryScope;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory fallback for PendingMemoryStore when MySQL is disabled. */
public final class InMemoryPendingMemoryStore implements PendingMemoryStore {
    private final Map<String, PendingMemoryItem> items = new ConcurrentHashMap<>();

    @Override
    public PendingMemoryItem enqueue(PendingMemoryItem item) {
        items.put(item.id(), item);
        return item;
    }

    @Override
    public List<PendingMemoryItem> listPending(String projectId, int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        return items.values().stream()
                .filter(item -> "pending".equals(item.status()))
                .filter(item -> projectId == null || projectId.isBlank()
                        || projectId.equals(item.projectId())
                        || MemoryScope.GLOBAL_PROJECT.equals(item.projectId())
                        || "global".equals(item.scope()))
                .sorted(Comparator.comparingLong(PendingMemoryItem::createdAtMs).reversed())
                .limit(cap)
                .toList();
    }

    @Override
    public Optional<PendingMemoryItem> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public synchronized boolean markApproved(String id, String memoryId, long updatedAtMs) {
        PendingMemoryItem current = items.get(id);
        if (current == null || !"pending".equals(current.status())) {
            return false;
        }
        items.put(id, new PendingMemoryItem(
                current.id(), current.projectId(), current.projectName(), current.scope(),
                current.kind(), current.statement(), current.confidence(), current.reason(),
                current.evidenceSource(), current.evidenceMessageIndex(), current.evidenceQuote(),
                current.supersedes(), current.sourceRunId(), "approved",
                memoryId == null ? "" : memoryId, current.createdAtMs(), updatedAtMs
        ));
        return true;
    }

    @Override
    public synchronized boolean markRejected(String id, long updatedAtMs) {
        PendingMemoryItem current = items.get(id);
        if (current == null || !"pending".equals(current.status())) {
            return false;
        }
        items.put(id, new PendingMemoryItem(
                current.id(), current.projectId(), current.projectName(), current.scope(),
                current.kind(), current.statement(), current.confidence(), current.reason(),
                current.evidenceSource(), current.evidenceMessageIndex(), current.evidenceQuote(),
                current.supersedes(), current.sourceRunId(), "rejected",
                current.memoryId(), current.createdAtMs(), updatedAtMs
        ));
        return true;
    }
}
