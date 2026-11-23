package io.github.jasoncai0.agent.memory;

import java.nio.file.Path;
import java.util.List;

/** Long-term memory sidecar. All methods are fail-soft for the agent loop. */
public interface MemoryService extends AutoCloseable {
    MemoryMode mode();

    MemoryHealth health();

    MemoryScope scope(Path workspace, String sessionId);

    List<MemoryItem> recall(String query, MemoryScope scope, int limit);

    String remember(MemoryCandidate candidate, MemoryScope scope);

    default List<MemoryItem> list(MemoryScope scope, int limit) {
        return List.of();
    }

    default String export(MemoryScope scope) {
        return "[]";
    }

    default void forget(String id) {
        // no-op
    }

    @Override
    default void close() {
        // no-op
    }
}
