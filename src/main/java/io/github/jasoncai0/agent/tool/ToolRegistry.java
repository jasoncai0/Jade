package io.github.jasoncai0.agent.tool;

import io.github.jasoncai0.agent.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        ToolSpec spec = tool.spec();
        if (tools.putIfAbsent(spec.name(), tool) != null) {
            throw new IllegalArgumentException("Duplicate tool name: " + spec.name());
        }
        return this;
    }

    public ToolRegistry registerAll(Collection<? extends Tool> tools) {
        tools.forEach(this::register);
        return this;
    }

    public void discover(ClassLoader classLoader) {
        int before = tools.size();
        for (ToolProvider provider : ServiceLoader.load(ToolProvider.class, classLoader)) {
            for (Tool tool : provider.tools()) {
                String name = tool.spec().name();
                if (tools.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "ServiceLoader tool conflicts with a built-in tool: " + name);
                }
                tools.put(name, tool);
            }
        }
        if (tools.size() > before) {
            io.github.jasoncai0.agent.config.Log.info("tools.discovered",
                    Map.of("count", tools.size() - before));
        }
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }

    public boolean concurrencySafe(String name) {
        return find(name).map(Tool::concurrencySafe).orElse(false);
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : tools.values()) {
            ToolSpec spec = tool.spec();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", spec.name());
            item.put("description", spec.description());
            item.put("parameters", spec.parameters());
            item.put("mutating", tool.mutatesWorkspace());
            result.add(item);
        }
        return result;
    }

    public ToolResult execute(String name, Map<String, Object> args, ToolContext context) {
        Optional<Tool> tool = find(name);
        if (tool.isEmpty()) {
            return ToolResult.error("Unknown tool '" + name + "'. Available tools: " + String.join(", ", tools.keySet()));
        }
        try {
            return tool.get().execute(context, args == null ? Map.of() : args);
        } catch (RuntimeException e) {
            return ToolResult.error("Tool " + name + " failed: " + e.getMessage());
        }
    }
}
