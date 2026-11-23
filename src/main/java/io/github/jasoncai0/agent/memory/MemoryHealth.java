package io.github.jasoncai0.agent.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/** Non-secret health metadata exposed by the API and CLI. */
public record MemoryHealth(
        boolean available,
        String provider,
        String mode,
        String endpoint,
        String message,
        long latencyMs
) {
    public MemoryHealth {
        provider = provider == null ? "" : provider;
        mode = mode == null ? "off" : mode;
        endpoint = endpoint == null ? "" : endpoint;
        message = message == null ? "" : message;
        if (latencyMs < 0) latencyMs = 0;
    }

    public static MemoryHealth disabled(String mode, String message) {
        return new MemoryHealth(false, MemorySettings.DEFAULT_PROVIDER, mode, "", message, 0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", available);
        map.put("provider", provider);
        map.put("mode", mode);
        map.put("endpoint", endpoint);
        map.put("message", message);
        map.put("latencyMs", latencyMs);
        return map;
    }
}
