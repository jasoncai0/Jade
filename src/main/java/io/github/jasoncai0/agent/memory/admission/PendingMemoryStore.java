package io.github.jasoncai0.agent.memory.admission;

import java.util.List;
import java.util.Optional;

/** Store for candidate memories waiting in the Pending review inbox. */
public interface PendingMemoryStore extends AutoCloseable {
    PendingMemoryItem enqueue(PendingMemoryItem item);

    List<PendingMemoryItem> listPending(String projectId, int limit);

    Optional<PendingMemoryItem> find(String id);

    boolean markApproved(String id, String memoryId, long updatedAtMs);

    boolean markRejected(String id, long updatedAtMs);

    @Override
    default void close() {
        // no-op
    }
}
