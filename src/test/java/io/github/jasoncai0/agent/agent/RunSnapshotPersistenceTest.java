package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.history.EventStore;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunSnapshotPersistenceTest {
    @Test
    void persistsTerminalSnapshotWhenRunFinishedIsEmitted() {
        RecordingStore store = new RecordingStore();
        RunEventLog log = new RunEventLog("run-snapshot", store);
        RunSnapshot snapshot = new RunSnapshot(
                "run-snapshot", "task", "ws", "SUCCEEDED", "checkpoint", "Checkpoint",
                3, 0, List.of(), List.of("src/Main.java"), Usage.ZERO,
                "done", null, "verified", 1L, 2L, ".agent/transcript.json");

        log.emit("run.finished", Map.of("snapshot", snapshot));

        assertEquals(1, store.snapshots.size());
        assertEquals("SUCCEEDED", store.snapshots.get(0).status());
        assertEquals("done", store.snapshots.get(0).finalAnswer());
    }

    @Test
    void clearsRestartErrorFromAlreadySuccessfulSnapshot() {
        RunSnapshot snapshot = new RunSnapshot(
                "run-old", "task", "ws", "SUCCEEDED", "checkpoint", "Checkpoint",
                3, 0, List.of(), List.of(), Usage.ZERO, "done",
                "Run was interrupted by a server restart", "verified", 1L, 2L,
                ".agent/transcript.json");
        RunState state = RunState.restore(snapshot);
        assertEquals("SUCCEEDED", state.status());
        assertEquals(null, state.error());
    }

    private static final class RecordingStore implements EventStore {
        private final List<RunSnapshot> snapshots = new ArrayList<>();

        @Override public void append(String runId, AgentEvent event) {}
        @Override public Page page(String runId, long before, int limit, boolean compact) {
            return new Page(List.of(), 0, false, 0, 0);
        }
        @Override public Optional<AgentEvent> event(String runId, long seq) { return Optional.empty(); }
        @Override public long lastSeq(String runId) { return 0; }
        @Override public long eventCount(String runId) { return 0; }
        @Override public void saveSnapshot(RunSnapshot snapshot) { snapshots.add(snapshot); }
        @Override public List<RunSnapshot> snapshots(int limit) { return List.copyOf(snapshots); }
        @Override public Optional<RunSnapshot> snapshot(String runId) { return Optional.empty(); }
        @Override public boolean isEmpty() { return true; }
        @Override public void importRun(RunSnapshot snapshot, List<AgentEvent> events) {}
        @Override public void close() {}
    }
}
