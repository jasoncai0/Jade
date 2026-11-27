package io.github.jasoncai0.agent.sandbox;

import java.nio.file.Path;

/** Session-scoped sandbox specification bound to one isolated workspace directory. */
public record SandboxSpec(
        String sessionId,
        Path workspaceRoot,
        SandboxSettings settings
) {
    public SandboxSpec {
        sessionId = sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot is required");
        }
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        settings = settings == null ? SandboxSettings.defaults() : settings;
    }
}
