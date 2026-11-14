package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.Verifier;
import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Language-agnostic compile/syntax-check gate. Java uses javac or Maven,
 * Python uses py_compile, JavaScript uses node --check.
 */
public final class CompileCodeTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "compile_code",
                "Compile or syntax-check the workspace using the detected language. "
                        + "Java: javac or Maven; Python: python3 -m py_compile; JavaScript: node --check. "
                        + "Use this for any language; compile_java remains a Java-only alias.",
                Schemas.object(Schemas.properties(
                        "language", Schemas.stringEnum(
                                "Optional language override. Omit to detect from source files.",
                                Language.ids()),
                        "timeoutSeconds", Schemas.integer("Compilation timeout.", 1, 600)
                ))
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        int timeout = ToolArgs.integer(args, "timeoutSeconds",
                context.settings().commandTimeoutSeconds(), 1, 600);
        String requested = ToolArgs.string(args, "language", "");
        Language language;
        if (requested.isBlank()) {
            language = context.verifier().detect(context.workspace());
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

        Verifier.VerificationResult result = context.verifier()
                .verify(context.workspace(), language, timeout);
        if (result.ok()) {
            context.markVerified();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("language", language.id());
        data.put("command", result.command());
        data.put("durationMs", result.durationMs());
        String output = "[" + language.displayName() + "] " + result.summary()
                + "\n$ " + result.command()
                + "\n" + result.output();
        return new ToolResult(result.ok(), output, data);
    }
}
