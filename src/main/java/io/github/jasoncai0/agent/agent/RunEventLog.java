package io.github.jasoncai0.agent.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class RunEventLog {
    private final List<AgentEvent> events = new ArrayList<>();
    private long nextSeq = 1;
    private boolean closed;

    /**
     * Rehydrates a closed log from persisted events. Sequence numbers are kept,
     * so SSE Last-Event-ID replay works after a restart.
     */
    public static RunEventLog restore(List<AgentEvent> persisted) {
        RunEventLog log = new RunEventLog();
        if (persisted != null) {
            log.events.addAll(persisted.stream()
                    .sorted(Comparator.comparingLong(AgentEvent::seq))
                    .toList());
            log.nextSeq = log.events.stream()
                    .mapToLong(AgentEvent::seq)
                    .max()
                    .orElse(0L) + 1;
        }
        log.closed = true;
        return log;
    }

    public synchronized AgentEvent emit(String type, Map<String, Object> data) {
        AgentEvent event = new AgentEvent(nextSeq++, System.currentTimeMillis(), type, data == null ? Map.of() : Map.copyOf(data));
        events.add(event);
        notifyAll();
        return event;
    }

    public synchronized List<AgentEvent> snapshot() {
        return List.copyOf(events);
    }

    public synchronized List<AgentEvent> after(long seq) {
        List<AgentEvent> result = new ArrayList<>();
        for (AgentEvent event : events) {
            if (event.seq() > seq) {
                result.add(event);
            }
        }
        return result;
    }

    public synchronized AgentEvent awaitAfter(long seq, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!closed && events.stream().noneMatch(event -> event.seq() > seq)) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            wait(remaining);
        }
        return events.stream().filter(event -> event.seq() > seq).findFirst().orElse(null);
    }

    public synchronized boolean closed() {
        return closed;
    }

    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized int size() {
        return events.size();
    }
}
