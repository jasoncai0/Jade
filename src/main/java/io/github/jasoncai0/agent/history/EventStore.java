package io.github.jasoncai0.agent.history;

import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.RunSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Durable session/event storage. Live runs still keep an in-memory event log
 * for SSE; history is read back in bounded pages from MySQL.
 */
public interface EventStore extends AutoCloseable {
    record Page(List<AgentEvent> events, long nextBefore, boolean hasMore, long lastSeq, long eventCount) {}

    void append(String runId, AgentEvent event);

    Page page(String runId, long before, int limit, boolean compact);

    Optional<AgentEvent> event(String runId, long seq);

    long lastSeq(String runId);

    long eventCount(String runId);

    void saveSnapshot(RunSnapshot snapshot);

    List<RunSnapshot> snapshots(int limit);

    Optional<RunSnapshot> snapshot(String runId);

    boolean isEmpty();

    void importRun(RunSnapshot snapshot, List<AgentEvent> events);

    default int deleteRuns(List<String> runIds) {
        return 0;
    }

    @Override
    void close();
}
