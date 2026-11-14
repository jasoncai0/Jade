package io.github.jasoncai0.agent.agent;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RunControl {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Thread thread;

    public void attach(Thread thread) {
        this.thread = thread;
        if (cancelled.get()) {
            thread.interrupt();
        }
    }

    public void cancel() {
        cancelled.set(true);
        Thread running = thread;
        if (running != null) {
            running.interrupt();
        }
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public void checkCancelled() {
        if (isCancelled()) {
            throw new RunCancelledException();
        }
    }

    public static final class RunCancelledException extends RuntimeException {
        public RunCancelledException() {
            super("Run cancelled");
        }
    }
}
