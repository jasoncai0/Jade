package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.CommandRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class RunCommandTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "run_command",
                "Run a shell command inside the workspace and return stdout, stderr and the exit code. "
                        + "Use it for builds, tests, git, and running programs. Destructive system commands are blocked.",
                Schemas.object(Schemas.properties(
                        "command", Schemas.string("Shell command to run in the workspace."),
                        "cwd", Schemas.string("Relative working directory. Defaults to the workspace root."),
                        "timeoutSeconds", Schemas.integer("Command timeout.", 1, 600)
                ), "command")
        );
    }

    @Override
    public boolean mutatesWorkspace() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String command = ToolArgs.requiredString(args, "command");
        if (command.length() > 4000) {
            return ToolResult.error("Command is too long (limit 4000 characters).");
        }
        String cwdArg = ToolArgs.string(args, "cwd", ".");
        Path cwd = context.workspace().resolve(cwdArg);
        if (!Files.isDirectory(cwd)) {
            return ToolResult.error("Working directory does not exist: " + cwdArg);
        }
        int timeout = ToolArgs.integer(args, "timeoutSeconds",
                context.settings().commandTimeoutSeconds(), 1, 600);
        context.markTouched();
        try {
            CommandRunner.CommandResult result = new CommandRunner()
                    .runShell(command, cwd, timeout, context.workspace().root());
            String output = "$ " + command + "\nexit=" + result.exitCode()
                    + "  time=" + result.durationMs() + "ms"
                    + (result.timedOut() ? "  (timed out)" : "") + "\n"
                    + result.combinedOutput(20_000);
            return new ToolResult(result.success(), output, Map.of(
                    "exitCode", result.exitCode(),
                    "timedOut", result.timedOut(),
                    "durationMs", result.durationMs()
            ));
        } catch (CommandRunner.CommandRejectedException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
