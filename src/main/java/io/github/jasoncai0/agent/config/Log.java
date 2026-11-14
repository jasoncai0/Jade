package io.github.jasoncai0.agent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-lines logging. Keep it dependency-free and make sure credentials never reach it.
 */
public final class Log {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();
    private static volatile boolean enabled = true;

    private Log() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static void info(String event) {
        write("INFO", event, Map.of(), null);
    }

    public static void info(String event, Map<String, Object> fields) {
        write("INFO", event, fields, null);
    }

    public static void warn(String event, Map<String, Object> fields) {
        write("WARN", event, fields, null);
    }

    public static void error(String event, Throwable error, Map<String, Object> fields) {
        write("ERROR", event, fields, error);
    }

    private static void write(String level, String event, Map<String, Object> fields, Throwable error) {
        if (!enabled) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", Instant.now().toString());
        record.put("level", level);
        record.put("event", event);
        if (fields != null) {
            fields.forEach((key, value) -> {
                if (!isSensitive(key)) {
                    record.put(key, value);
                }
            });
        }
        if (error != null) {
            record.put("errorType", error.getClass().getSimpleName());
            record.put("error", safeMessage(error));
        }
        String line;
        try {
            line = JSON.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            line = "{\"level\":\"" + level + "\",\"event\":\"" + event + "\"}";
        }
        synchronized (LOCK) {
            System.out.println(line);
            if (error != null) {
                System.out.flush();
                error.printStackTrace(System.err);
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "" : message.substring(0, Math.min(message.length(), 500));
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        return lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("authorization")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("password");
    }
}
