package io.github.jasoncai0.agent.tool;

import io.github.jasoncai0.agent.llm.ToolSpec;

import java.util.Map;

public interface Tool {
    ToolSpec spec();

    ToolResult execute(ToolContext context, Map<String, Object> args);

    default boolean mutatesWorkspace() {
        return false;
    }

    /**
     * True only when the tool is safe to run alongside other concurrency-safe
     * tools. Unknown or mutating tools stay on the serial path.
     */
    default boolean concurrencySafe() {
        return false;
    }
}
