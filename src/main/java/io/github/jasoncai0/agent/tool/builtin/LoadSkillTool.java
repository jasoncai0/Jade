package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.Map;
import java.util.Optional;

public final class LoadSkillTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "load_skill",
                "Load the full instructions of an installed skill by name. The catalog is in the system prompt.",
                Schemas.object(Schemas.properties(
                        "name", Schemas.string("Exact skill name from the catalog.")
                ), "name")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String name = ToolArgs.requiredString(args, "name").strip();
        Optional<Skill> skill = context.skills().find(name);
        if (skill.isEmpty()) {
            return ToolResult.error("Unknown skill '" + name + "'. Available skills:\n"
                    + context.skills().catalog());
        }
        context.skillLoaded(name);
        return ToolResult.ok(skill.get().render(), Map.of("skill", name));
    }
}
