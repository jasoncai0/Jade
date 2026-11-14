package io.github.jasoncai0.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolArgs {
    private ToolArgs() {
    }

    public static String requiredString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return String.valueOf(value);
    }

    public static String string(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    public static int integer(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object value = args.get(key);
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null && !String.valueOf(value).isBlank()) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Argument '" + key + "' must be an integer");
            }
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Argument '" + key + "' must be between " + min + " and " + max);
        }
        return parsed;
    }

    public static boolean bool(Map<String, Object> args, String key, boolean fallback) {
        Object value = args.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static List<Object> list(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> items) {
            return new ArrayList<>(items);
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be an array");
    }
}
