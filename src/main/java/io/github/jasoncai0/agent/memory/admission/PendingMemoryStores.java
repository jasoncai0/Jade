package io.github.jasoncai0.agent.memory.admission;

import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.config.MysqlSettings;

import java.util.Map;

/** Factory for durable MySQL pending memory store with graceful in-memory fallback. */
public final class PendingMemoryStores {
    private PendingMemoryStores() {
    }

    public static PendingMemoryStore create(MysqlSettings settings) {
        if (settings == null || settings.url() == null || settings.url().isBlank()) {
            return new InMemoryPendingMemoryStore();
        }
        try {
            return new MySqlPendingMemoryStore(settings);
        } catch (RuntimeException e) {
            Log.warn("memory.pending_store_fallback", Map.of("error", String.valueOf(e.getMessage())));
            return new InMemoryPendingMemoryStore();
        }
    }
}
