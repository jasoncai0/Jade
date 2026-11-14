package io.github.jasoncai0.agent.language;

import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageTest {
    @TempDir
    Path temp;

    @Test
    void resolvesAliasesAndExtensions() {
        assertEquals(Language.PYTHON, Language.fromId("python3").orElseThrow());
        assertEquals(Language.PYTHON, Language.fromId("py").orElseThrow());
        assertEquals(Language.JAVASCRIPT, Language.fromId("node").orElseThrow());
        assertEquals(Language.JAVASCRIPT, Language.fromPath("src/app.js"));
        assertEquals(Language.JAVA, Language.fromPath("Main.java"));
        assertNull(Language.fromPath("README.md"));
    }

    @Test
    void detectsBySourceCountAndTieOrder() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("detect"));
        workspace.writeText("first.py", "print('one')\n");
        workspace.writeText("second.py", "print('two')\n");
        workspace.writeText("app.js", "console.log('three');\n");
        workspace.writeText("Main.java", "class Main {}\n");
        assertEquals(Language.PYTHON, Language.detect(workspace.sourceFiles(".java", ".py", ".js")));

        Workspace tie = Workspace.open(temp.resolve("tie"));
        tie.writeText("app.js", "console.log('js');\n");
        tie.writeText("main.py", "print('py')\n");
        assertEquals(Language.JAVA.equals(Language.detect(tie.sourceFiles(".java", ".py", ".js"))),
                false, "a Python/JavaScript tie must not become Java");
        assertEquals(Language.PYTHON, Language.detect(tie.sourceFiles(".java", ".py", ".js")));
    }

    @Test
    void exposesSupportedLanguagesAndEntryCandidates() {
        assertEquals(List.of("java", "python", "javascript"), Language.ids());
        assertEquals(List.of("Java", "Python", "JavaScript"), Language.displayNames());
        assertEquals(".py", Language.PYTHON.extension());
        assertEquals(List.of("main.py", "src/main.py", "app.py"), Language.PYTHON.entryCandidates());
    }
}
