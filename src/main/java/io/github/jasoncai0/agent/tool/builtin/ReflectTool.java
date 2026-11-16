package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured reflection tool. It does not mutate the workspace; it forces the
 * model to state a diagnosis, evidence, attempts, and one next action when the
 * no-progress policy asks it to stop and think.
 */
public final class ReflectTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "reflect",
                "Use when stuck, repeating an action, or asked for a reflection checkpoint. "
                        + "State the diagnosis, concrete evidence, attempts, one next action, "
                        + "and whether the task must stop for user input. This tool never edits files.",
                Schemas.object(Schemas.properties(
                        "diagnosis", Schemas.string("What is blocking progress?"),
                        "evidence", Schemas.array("Concrete evidence from commands/files.",
                                Schemas.string("One evidence item.")),
                        "attempts", Schemas.array("What has already been tried.",
                                Schemas.string("One attempted action.")),
                        "next_action", Schemas.string("One specific next action that can change the outcome."),
                        "should_stop", Schemas.bool("True if the task cannot proceed without user input.")
                ), "diagnosis", "evidence", "attempts", "next_action", "should_stop")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String diagnosis = ToolArgs.requiredString(args, "diagnosis");
        String nextAction = ToolArgs.requiredString(args, "next_action");
        List<Object> evidence = ToolArgs.list(args, "evidence");
        List<Object> attempts = ToolArgs.list(args, "attempts");
        boolean shouldStop = ToolArgs.bool(args, "should_stop", false);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("diagnosis", diagnosis);
        data.put("nextAction", nextAction);
        data.put("shouldStop", shouldStop);
        data.put("evidence", evidence);
        data.put("attempts", attempts);

        String output = "Reflection recorded.\n"
                + "diagnosis: " + diagnosis + "\n"
                + "next_action: " + nextAction + "\n"
                + "should_stop: " + shouldStop + "\n"
                + "evidence_items: " + evidence.size() + "\n"
                + "attempts: " + attempts.size();
        return new ToolResult(true, output, data);
    }

    @Override
    public boolean mutatesWorkspace() {
        return false;
    }
}
