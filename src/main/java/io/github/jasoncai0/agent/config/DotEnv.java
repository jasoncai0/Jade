package io.github.jasoncai0.agent.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A deliberately small .env reader: KEY=VALUE, optional export, optional quotes.
 * It never overrides real environment variables (AppConfig applies that precedence).
 */
final class DotEnv {
    private DotEnv() {
    }

    static Map<String, String> read(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            if (Files.size(file) > 64 * 1024) {
                return values;
            }
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                values.putIfAbsent(key, value);
            }
        } catch (IOException ignored) {
            // A malformed or unreadable .env should not prevent startup. Missing configuration
            // is reported with a clear message when a run is started.
        }
        return values;
    }
}
