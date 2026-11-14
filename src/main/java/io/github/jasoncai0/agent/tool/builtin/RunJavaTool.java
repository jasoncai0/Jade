package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.Verifier;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.CommandRunner;

import java.util.Map;

public final class RunJavaTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "run_java",
                "Compile if needed, then run a Java main class and capture its output. "
                        + "Use the fully qualified class name, for example Hello or demo.Main.",
                Schemas.object(Schemas.properties(
                        "mainClass", Schemas.string("Fully qualified class with a main method."),
                        "args", Schemas.string("Space-separated program arguments."),
                        "timeoutSeconds", Schemas.integer("Run timeout.", 1, 600)
                ), "mainClass")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String mainClass = ToolArgs.requiredString(args, "mainClass").strip();
        if (!mainClass.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
            return ToolResult.error("Invalid Java class name: " + mainClass);
        }
        String programArgs = ToolArgs.string(args, "args", "");
        int timeout = ToolArgs.integer(args, "timeoutSeconds",
                context.settings().commandTimeoutSeconds(), 1, 600);

        if (context.dirty() || !context.verified()) {
            Verifier.VerificationResult compiled = context.verifier()
                    .verify(context.workspace(), context.settings().commandTimeoutSeconds());
            if (!compiled.ok()) {
                return ToolResult.error("Compilation failed, so the program was not run.\n"
                        + compiled.summary() + "\n" + compiled.output());
            }
            context.markVerified();
        }

        try {
            CommandRunner.CommandResult result = context.verifier()
                    .runMain(context.workspace(), mainClass, programArgs, timeout);
            String output = "$ java " + mainClass + (programArgs.isBlank() ? "" : " " + programArgs)
                    + "\nexit=" + result.exitCode() + "  time=" + result.durationMs() + "ms\n"
                    + result.combinedOutput(20_000);
            return new ToolResult(result.success(), output, Map.of(
                    "exitCode", result.exitCode(),
                    "mainClass", mainClass,
                    "durationMs", result.durationMs()
            ));
        } catch (CommandRunner.CommandRejectedException e) {
            return ToolResult.error("Cannot run Java: " + e.getMessage());
        }
    }
}
