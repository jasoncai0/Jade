package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.SubagentService;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parent-only tool: delegates a bounded, read-only investigation to a child
 * run and returns only its summary. The child has no write, shell, compile or
 * delegate tools, so an injected instruction cannot turn it into a writer.
 */
public final class DelegateTaskTool implements Tool {
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MIN_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 900;
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;

    private final SubagentService subagents;
    private final AgentRun parent;

    public DelegateTaskTool(SubagentService subagents, AgentRun parent) {
        this.subagents = subagents;
        this.parent = parent;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "delegate_task",
                "Delegate a bounded, read-only investigation to a child agent. The child can "
                        + "list/read/search files and load skills, but cannot write, run commands, "
                        + "compile, or delegate further. Use it to keep large searches out of this "
                        + "conversation; you receive a compact summary and a child run id.",
                Schemas.object(Schemas.properties(
                        "task", Schemas.string("What to investigate; be specific about the question "
                                + "and the files or directories to inspect."),
                        "name", Schemas.string("Optional kebab-case label for the child session."),
                        "timeoutSeconds", Schemas.integer("Wall-clock budget for the child.",
                                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
                ), "task")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        try {
            String task = ToolArgs.requiredString(args, "task").trim();
            String name = ToolArgs.string(args, "name", "subagent");
            if (!NAME.matcher(name).matches() || name.length() > 32) {
                throw new IllegalArgumentException(
                        "name must be kebab-case (lowercase letters, digits, hyphens), at most 32 characters");
            }
            int timeout = ToolArgs.integer(args, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS,
                    MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
            SubagentService.DelegationResult result = subagents.start(parent, task, name, timeout);

            StringBuilder text = new StringBuilder();
            text.append("status: ").append(result.status()).append('\n');
            text.append("childRunId: ").append(result.childRunId()).append('\n');
            text.append("tokens: ").append(result.usage().totalTokens()).append('\n');
            if (!result.filesRead().isEmpty()) {
                text.append("filesRead:\n");
                result.filesRead().forEach(path -> text.append("- ").append(path).append('\n'));
            }
            text.append("summary:\n").append(result.summary());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("childRunId", result.childRunId());
            data.put("status", result.status());
            data.put("tokens", result.usage().totalTokens());
            data.put("filesRead", result.filesRead());
            data.put("artifactPath", ".agent/subagents/" + result.childRunId() + "/transcript.json");
            return ToolResult.ok(text.toString(), data);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
