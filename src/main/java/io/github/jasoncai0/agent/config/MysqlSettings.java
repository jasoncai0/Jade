package io.github.jasoncai0.agent.config;

/**
 * MySQL is Jade's durable session/event store. The defaults target the
 * local docker-compose service on port 3307.
 */
public record MysqlSettings(String url, String user, String password) {
    public static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3307/jade?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    public MysqlSettings {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MySQL url must not be blank");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("MySQL user must not be blank");
        }
        if (password == null) {
            password = "";
        }
    }
}
