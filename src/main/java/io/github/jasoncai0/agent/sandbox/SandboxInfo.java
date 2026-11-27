package io.github.jasoncai0.agent.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public status descriptor for the active execution backend. */
public record SandboxInfo(
        String backend,
        String modeName,
        boolean active,
        boolean containerIsolated,
        boolean networkEnabled,
        String description,
        String containerName,
        String image
) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("backend", backend);
        map.put("mode", modeName);
        map.put("active", active);
        map.put("containerIsolated", containerIsolated);
        map.put("networkEnabled", networkEnabled);
        map.put("description", description);
        if (containerName != null && !containerName.isBlank()) {
            map.put("containerName", containerName);
        }
        if (image != null && !image.isBlank()) {
            map.put("image", image);
        }
        return map;
    }
}
