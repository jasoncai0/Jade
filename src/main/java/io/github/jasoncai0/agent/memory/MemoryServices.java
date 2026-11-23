package io.github.jasoncai0.agent.memory;

import io.github.jasoncai0.agent.config.Log;

import java.util.Map;

/** Creates the configured memory backend, falling back to Noop when unavailable. */
public final class MemoryServices {
    private MemoryServices() {
    }

    public static MemoryService create(MemorySettings settings) {
        if (settings == null) {
            return NoopMemoryService.off();
        }
        MemorySettings safe = settings;
        if (!safe.active()) {
            return new NoopMemoryService(safe.mode(),
                    MemoryHealth.disabled(safe.mode().id(),
                            safe.mode() == MemoryMode.OFF ? "memory mode off" : "memory URL is empty"));
        }
        Mem0HttpMemoryService client = new Mem0HttpMemoryService(safe);
        MemoryHealth health = client.health();
        client.close();
        if (health.available()) {
            Mem0HttpMemoryService active = new Mem0HttpMemoryService(safe);
            Log.info("memory.ready", Map.of("provider", safe.provider(), "endpoint", safe.baseUrl()));
            return active;
        }
        Log.warn("memory.unavailable", Map.of("endpoint", safe.baseUrl(), "reason", health.message()));
        return new NoopMemoryService(safe.mode(),
                new MemoryHealth(false, health.provider(), safe.mode().id(),
                        health.endpoint(), health.message(), health.latencyMs()));
    }
}
