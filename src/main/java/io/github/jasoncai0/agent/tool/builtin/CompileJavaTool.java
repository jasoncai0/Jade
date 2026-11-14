package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.agent.Verifier;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.util.Map;

public final class CompileJavaTool implements Tool {
    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "compile_java",
                "Compile every Java source in the workspace. Uses Maven when pom.xml exists, otherwise javac. "
                        + "Returns real compiler output, including errors.",
                Schemas.object(Schemas.properties())
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        Verifier.VerificationResult result = context.verifier()
                .verify(context.workspace(), context.settings().commandTimeoutSeconds());
        if (result.ok()) {
            context.markVerified();
        }
        String output = result.summary() + "\n$ " + result.command()
                + "\n" + result.output();
        return new ToolResult(result.ok(), output, Map.of(
                "command", result.command(),
                "durationMs", result.durationMs()
        ));
    }
}
