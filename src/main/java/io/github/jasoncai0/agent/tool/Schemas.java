package io.github.jasoncai0.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Schemas {
    private Schemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> stringEnum(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    public static Map<String, Object> integer(String description, int min, int max) {
        return Map.of("type", "integer", "description", description, "minimum", min, "maximum", max);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> array(String description, Map<String, Object> items) {
        return Map.of("type", "array", "description", description, "items", items);
    }

    public static Map<String, Object> properties(Object... keyValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            properties.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return properties;
    }

    public static List<String> list(String... values) {
        return new ArrayList<>(List.of(values));
    }
}
