package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Usage;

/**
 * Coalesces streaming token deltas before publishing them to SSE: at most one
 * frame per {@value #DELTA_INTERVAL_MS} ms or per {@value #DELTA_CHUNK_CHARS}
 * characters, discarding buffered partial output on failure.
 */
final class DeltaThrottle implements LlmClient.StreamListener {
    private static final long DELTA_INTERVAL_MS = 50;
    private static final int DELTA_CHUNK_CHARS = 512;

    private final LiveDeltaBus bus;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private long lastFlush = System.currentTimeMillis();

    private DeltaThrottle(LiveDeltaBus bus) {
        this.bus = bus;
    }

    static ChatResult stream(LlmClient llm, ChatRequest request, LiveDeltaBus bus) {
        if (bus == null) {
            return llm.stream(request, LlmClient.StreamListener.noop());
        }
        DeltaThrottle throttle = new DeltaThrottle(bus);
        ChatResult result;
        try {
            result = llm.stream(request, throttle);
        } catch (RuntimeException e) {
            throttle.discard();
            bus.finish();
            throw e;
        }
        throttle.flush();
        bus.finish();
        return result;
    }

    @Override
    public void onText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            text.append(delta);
        }
        maybeFlush();
    }

    @Override
    public void onReasoning(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            reasoning.append(delta);
        }
        maybeFlush();
    }

    @Override
    public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
        // Tool arguments land in the durable log.
    }

    @Override
    public void onUsage(Usage usage) {
        // Usage lands in the durable log with the finished assistant message.
    }

    private void maybeFlush() {
        long now = System.currentTimeMillis();
        synchronized (this) {
            int pending = text.length() + reasoning.length();
            if (pending == 0) {
                return;
            }
            if (pending >= DELTA_CHUNK_CHARS || now - lastFlush >= DELTA_INTERVAL_MS) {
                flushLocked();
            }
        }
    }

    private void flush() {
        synchronized (this) {
            flushLocked();
        }
    }

    private void discard() {
        synchronized (this) {
            text.setLength(0);
            reasoning.setLength(0);
        }
    }

    private void flushLocked() {
        if (text.length() == 0 && reasoning.length() == 0) {
            return;
        }
        bus.publish(text.length() == 0 ? "" : text.toString(),
                reasoning.length() == 0 ? null : reasoning.toString());
        text.setLength(0);
        reasoning.setLength(0);
        lastFlush = System.currentTimeMillis();
    }
}
