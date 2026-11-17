package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.history.EventStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RunEventLog {
    private final List<AgentEvent> events = new ArrayList<>();
    private final List<Runnable> wakeups = new CopyOnWriteArrayList<>();
    private final String runId;
    private final EventStore store;
    private long nextSeq = 1;
    private boolean closed;

    public RunEventLog() {
        this(null, null);
    }

    /** Live log backed by a durable store. */
    public RunEventLog(String runId, EventStore store) {
        this.runId = runId;
        this.store = store;
    }

    /** Closed log restored from durable history; seq continues after lastSeq. */
    public static RunEventLog restored(String runId, EventStore store, long lastSeq) {
        RunEventLog log = new RunEventLog(runId, store);
        log.nextSeq = Math.max(1, lastSeq + 1);
        log.closed = true;
        return log;
    }

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

    public AgentEvent emit(String type, Map<String, Object> data) {
        AgentEvent event;
        synchronized (this) {
            event = new AgentEvent(nextSeq++, System.currentTimeMillis(), type,
                    data == null ? Map.of() : Map.copyOf(data));
            events.add(event);
            if (store != null && runId != null) {
                store.append(runId, event);
            }
            notifyAll();
        }
        // Listeners run outside the lock: they only push a wake-up signal.
        for (Runnable wakeup : wakeups) {
            wakeup.run();
        }
        return event;
    }

    /**
     * Appends a pre-built event, keeping its original sequence and timestamp.
     * Used when a forked session copies its parent's history.
     */
    public synchronized AgentEvent append(AgentEvent event) {
        events.add(event);
        nextSeq = Math.max(nextSeq, event.seq() + 1);
        if (store != null && runId != null) {
            store.append(runId, event);
        }
        return event;
    }

    /** Registers a cheap callback fired after every emit, outside the lock. */
    public void subscribe(Runnable wakeup) {
        wakeups.add(wakeup);
    }

    public void unsubscribe(Runnable wakeup) {
        wakeups.remove(wakeup);
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

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        for (Runnable wakeup : wakeups) {
            wakeup.run();
        }
    }

    /** Reopens a restored log when the user continues a saved session. */
    public synchronized void open() {
        closed = false;
        notifyAll();
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized long lastSeq() {
        return nextSeq - 1;
    }
}
