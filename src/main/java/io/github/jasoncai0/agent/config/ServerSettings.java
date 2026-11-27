package io.github.jasoncai0.agent.config;

import java.util.Locale;

public record ServerSettings(String host, int port, String authToken) {
    public ServerSettings(String host, int port) {
        this(host, port, "");
    }

    public ServerSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("AGENT_HOST must not be blank");
        }
        host = host.trim();
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("AGENT_PORT must be between 0 and 65535");
        }
        authToken = authToken == null ? "" : authToken.trim();
    }

    public boolean isLoopback() {
        return isLoopbackHost(host);
    }

    public boolean hasAuthToken() {
        return !authToken.isEmpty();
    }

    public static boolean isLoopbackHost(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1")
                || normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("[::1]");
    }
}
