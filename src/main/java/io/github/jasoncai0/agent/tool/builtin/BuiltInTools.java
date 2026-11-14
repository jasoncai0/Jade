package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolRegistry;

import java.util.List;

public final class BuiltInTools {
    private BuiltInTools() {
    }

    public static List<Tool> all() {
        return List.of(
                new ListFilesTool(),
                new ReadFileTool(),
                new WriteFileTool(),
                new EditFileTool(),
                new SearchTextTool(),
                new RunCommandTool(),
                new CompileJavaTool(),
                new RunJavaTool(),
                new UpdatePlanTool(),
                new LoadSkillTool()
        );
    }

    public static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(all());
        registry.discover(Thread.currentThread().getContextClassLoader());
        return registry;
    }
}
