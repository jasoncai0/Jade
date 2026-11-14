package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.Verifier;
import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.CommandRunner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Language-agnostic execution tool. Java entry is a class name; Python and
 * JavaScript entry is a workspace-relative file.
 */
public final class RunCodeTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "run_code",
                "Compile if needed, then run a program. Java entry is a fully qualified main class; "
                        + "Python/JavaScript entry is a relative file such as main.py or main.js. "
                        + "Omit language to detect it; entry defaults to Main.java/Main.py/Main.js conventions.",
                Schemas.object(Schemas.properties(
                        "language", Schemas.stringEnum(
                                "Optional language id: java, python, or javascript.",
                                Language.ids()),
                        "entry", Schemas.string(
                                "Java main class, or Python/JavaScript relative source file."),
                        "args", Schemas.string("Space-separated program arguments."),
                        "timeoutSeconds", Schemas.integer("Run timeout.", 1, 600)
                ))
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        String requested = ToolArgs.string(args, "language", "");
        String entry = ToolArgs.string(args, "entry", "").strip();
        Language language;
        if (requested.isBlank()) {
            Language byPath = Language.fromPath(entry);
            language = byPath != null ? byPath : context.verifier().detect(context.workspace());
        } else {
            Optional<Language> parsed = Language.fromId(requested);
            if (parsed.isEmpty()) {
                return ToolResult.error("Unsupported language '" + requested
                        + "'. Use one of: " + String.join(", ", Language.ids()) + ".");
            }
            language = parsed.get();
        }
        if (language == null) {
            return ToolResult.error("No supported source files found. Create a .java, .py, or .js file first.");
        }
        if (language == Language.JAVA) {
            if (entry.isBlank()) {
                entry = "Main";
            }
            if (!entry.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
                return ToolResult.error("Invalid Java class name: " + entry);
            }
        }

        String programArgs = ToolArgs.string(args, "args", "");
        int timeout = ToolArgs.integer(args, "timeoutSeconds",
                context.settings().commandTimeoutSeconds(), 1, 600);

        if (context.dirty() || !context.verified()) {
            Verifier.VerificationResult compiled = context.verifier()
                    .verify(context.workspace(), language, context.settings().commandTimeoutSeconds());
            if (!compiled.ok()) {
                return ToolResult.error("Compilation failed, so the program was not run.\n"
                        + compiled.summary() + "\n" + compiled.output());
            }
            context.markVerified();
        }

        try {
            CommandRunner.CommandResult result = context.verifier()
                    .run(context.workspace(), language, entry, programArgs, timeout);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("language", language.id());
            data.put("entry", entry);
            data.put("exitCode", result.exitCode());
            data.put("durationMs", result.durationMs());
            String output = "$ " + result.commandLine()
                    + "\nexit=" + result.exitCode() + "  time=" + result.durationMs() + "ms\n"
                    + result.combinedOutput(20_000);
            return new ToolResult(result.success(), output, data);
        } catch (CommandRunner.CommandRejectedException | IllegalArgumentException e) {
            return ToolResult.error("Cannot run " + language.displayName() + ": " + e.getMessage());
        }
    }
}
