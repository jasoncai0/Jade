package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.PlanItem;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class UpdatePlanTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "update_plan",
                "Replace the visible execution plan. Keep one item in_progress and mark items completed as you go.",
                Schemas.object(Schemas.properties(
                        "plan", Schemas.array("Ordered plan items.", Schemas.object(Schemas.properties(
                                "step", Schemas.string("Short description of the step."),
                                "status", Schemas.stringEnum("Current status.",
                                        List.of("pending", "in_progress", "completed"))
                        ), "step", "status"))
                ), "plan")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        List<Object> raw = ToolArgs.list(args, "plan");
        if (raw.isEmpty()) {
            return ToolResult.error("Plan must contain at least one item.");
        }
        if (raw.size() > 20) {
            return ToolResult.error("Plan is limited to 20 items.");
        }
        List<PlanItem> plan = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                return ToolResult.error("Each plan item must be an object with step and status.");
            }
            Object step = map.get("step");
            Object status = map.get("status");
            if (step == null) {
                return ToolResult.error("Plan item is missing 'step'.");
            }
            try {
                plan.add(new PlanItem(String.valueOf(step), status == null ? "pending" : String.valueOf(status)));
            } catch (IllegalArgumentException e) {
                return ToolResult.error(e.getMessage());
            }
        }
        context.updatePlan(plan);
        StringBuilder out = new StringBuilder("Plan updated:\n");
        for (PlanItem item : plan) {
            out.append("- [").append(item.status()).append("] ").append(item.step()).append('\n');
        }
        return ToolResult.ok(out.toString().stripTrailing(), Map.of("items", plan.size()));
    }
}
