package io.github.jasoncai0.agent.tool;

import io.github.jasoncai0.agent.llm.ToolSpec;

import java.util.Map;

public interface Tool {
    ToolSpec spec();

    ToolResult execute(ToolContext context, Map<String, Object> args);

    default boolean mutatesWorkspace() {
        return false;
    }
}
