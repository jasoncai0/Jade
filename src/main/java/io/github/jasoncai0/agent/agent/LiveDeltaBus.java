package io.github.jasoncai0.agent.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort live channel for assistant text while a model request is still
 * streaming. Deltas never enter the durable event log or MySQL: they exist to
 * make the browser feel alive, and they may be dropped when nobody keeps up.
 * The {@code assistant} event remains the single source of truth.
 */
public final class LiveDeltaBus {
    /** How many recent deltas are kept for late subscribers and re-drains. */
    static final int RECENT_LIMIT = 256;

    private final List<Runnable> wakeups = new CopyOnWriteArrayList<>();
    private final Deque<Delta> recent = new ArrayDeque<>();
    private final AtomicLong nextSeq = new AtomicLong(1);
    private boolean active;

    public record Delta(long seq, String text, String reasoning) {
    }

    /**
     * Publishes one already-throttled chunk. Text and reasoning may both be
     * non-null; at least one is non-empty.
     */
    public void publish(String text, String reasoning) {
        Delta delta = new Delta(nextSeq.getAndIncrement(), text, reasoning);
        synchronized (this) {
            recent.addLast(delta);
            while (recent.size() > RECENT_LIMIT) {
                recent.removeFirst();
            }
            active = true;
        }
        for (Runnable wakeup : wakeups) {
            wakeup.run();
        }
    }

    /** Marks the end of a streaming request so late subscribers stop waiting. */
    public void finish() {
        synchronized (this) {
            active = false;
        }
    }

    public synchronized boolean active() {
        return active;
    }

    /** Deltas newer than {@code seq}, oldest first. */
    public synchronized List<Delta> after(long seq) {
        List<Delta> result = new ArrayList<>();
        for (Delta delta : recent) {
            if (delta.seq() > seq) {
                result.add(delta);
            }
        }
        return result;
    }

    public long lastSeq() {
        return nextSeq.get() - 1;
    }

    /** Registers a cheap callback fired on every publish, not on finish. */
    public void subscribe(Runnable wakeup) {
        wakeups.add(wakeup);
    }

    public void unsubscribe(Runnable wakeup) {
        wakeups.remove(wakeup);
    }
}
