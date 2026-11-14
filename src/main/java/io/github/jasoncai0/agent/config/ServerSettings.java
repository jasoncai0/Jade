package io.github.jasoncai0.agent.config;

public record ServerSettings(String host, int port) {
    public ServerSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("AGENT_HOST must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("AGENT_PORT must be between 0 and 65535");
        }
    }
}
