package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.language.Language;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiLanguageVerifierTest {
    @TempDir
    Path temp;

    private final Verifier verifier = new Verifier();

    @Test
    void verifiesAndRunsPython() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("python"));
        workspace.writeText("main.py", "print('hello-python')\n");
        assertEquals(Language.PYTHON, verifier.detect(workspace));

        Verifier.VerificationResult compiled = verifier.verify(workspace, 30);
        assertTrue(compiled.ok(), compiled.summary() + "\n" + compiled.output());
        assertTrue(compiled.summary().contains("Python syntax check passed"));

        CommandRunner.CommandResult run = verifier.run(workspace, Language.PYTHON, "main.py", "", 30);
        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("hello-python"), run.stdout());
    }

    @Test
    void verifiesAndRunsJavaScript() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("javascript"));
        workspace.writeText("main.js", "console.log('hello-javascript');\n");
        assertEquals(Language.JAVASCRIPT, verifier.detect(workspace));

        Verifier.VerificationResult compiled = verifier.verify(workspace, Language.JAVASCRIPT, 30);
        assertTrue(compiled.ok(), compiled.summary() + "\n" + compiled.output());
        assertTrue(compiled.summary().contains("JavaScript syntax check passed"));

        CommandRunner.CommandResult run = verifier.run(workspace, Language.JAVASCRIPT, "main.js", "", 30);
        assertEquals(0, run.exitCode());
        assertTrue(run.stdout().contains("hello-javascript"), run.stdout());
    }

    @Test
    void verifiesAndRunsGo() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("go"));
        workspace.writeText("main.go", """
                package main

                import "fmt"

                func main() {
                    fmt.Println("hello-go")
                }
                """);
        assertEquals(Language.GO, verifier.detect(workspace));

        Verifier.VerificationResult compiled = verifier.verify(workspace, Language.GO, 60);
        assertTrue(compiled.ok(), compiled.summary() + "\n" + compiled.output());
        assertTrue(compiled.summary().contains("go build passed"));
        assertTrue(workspace.exists("go.mod"), "module-less Go demos get a minimal go.mod");

        CommandRunner.CommandResult run = verifier.run(workspace, Language.GO, "", "", 60);
        assertEquals(0, run.exitCode(), run.stderr());
        assertTrue(run.stdout().contains("hello-go"), run.stdout());
    }

    @Test
    void reportsSyntaxErrorsFromTheRealInterpreter() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("broken-python"));
        workspace.writeText("main.py", "def broken(:\n    pass\n");
        Verifier.VerificationResult result = verifier.verify(workspace, Language.PYTHON, 30);
        assertFalse(result.ok());
        assertTrue(result.output().toLowerCase().contains("syntaxerror"), result.output());
    }

    @Test
    void checksEveryJavaScriptFileNotOnlyTheFirst() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("javascript-multi"));
        workspace.writeText("a.js", "console.log('ok');\n");
        workspace.writeText("b.js", "const broken = ;\n");
        Verifier.VerificationResult result = verifier.verify(workspace, Language.JAVASCRIPT, 30);
        assertFalse(result.ok(), result.summary());
        assertTrue(result.output().contains("b.js"), result.output());
    }

    @Test
    void registersGenericAndJavaAliasTools() {
        assertEquals(12, BuiltInTools.all().size());
        assertTrue(BuiltInTools.all().stream().anyMatch(tool -> tool.spec().name().equals("compile_code")));
        assertTrue(BuiltInTools.all().stream().anyMatch(tool -> tool.spec().name().equals("run_code")));
        assertTrue(BuiltInTools.all().stream().anyMatch(tool -> tool.spec().name().equals("compile_java")));
        assertTrue(BuiltInTools.all().stream().anyMatch(tool -> tool.spec().name().equals("run_java")));
    }
}
