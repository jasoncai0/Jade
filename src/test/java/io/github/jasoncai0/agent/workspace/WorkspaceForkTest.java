package io.github.jasoncai0.agent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceForkTest {
    @TempDir
    Path temp;

    @Test
    void copiesSourcesSkipsBuildOutputAndKeepsSpilledToolResults() throws Exception {
        Path source = temp.resolve("source");
        Path target = temp.resolve("target");
        Files.createDirectories(source.resolve("src"));
        Files.createDirectories(source.resolve(".git"));
        Files.createDirectories(source.resolve("target"));
        Files.createDirectories(source.resolve("node_modules/pkg"));
        Files.createDirectories(source.resolve(".agent/tool-results/session"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}");
        Files.writeString(source.resolve(".git/config"), "secret");
        Files.writeString(source.resolve("target/Main.class"), "bytecode");
        Files.writeString(source.resolve("node_modules/pkg/index.js"), "module");
        Files.writeString(source.resolve(".agent/tool-results/session/1.txt"), "spilled output");
        Files.createDirectories(target);

        WorkspaceFork.Result result = WorkspaceFork.copy(source, target);

        assertEquals("class Main {}", Files.readString(target.resolve("src/Main.java")));
        assertFalse(Files.exists(target.resolve(".git")));
        assertFalse(Files.exists(target.resolve("target")));
        assertFalse(Files.exists(target.resolve("node_modules")));
        assertEquals("spilled output",
                Files.readString(target.resolve(".agent/tool-results/session/1.txt")));
        assertEquals(1, result.files());
        assertEquals(1, result.toolResultFiles());
    }

    @Test
    void refusesSymlinksAndCleansUpThePartialTarget() throws Exception {
        Path source = temp.resolve("source-link");
        Path target = temp.resolve("target-link");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Real.java"), "class Real {}");
        Path link = source.resolve("src/Link.java");
        try {
            Files.createSymbolicLink(link, source.resolve("src/Real.java"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // Filesystem without symlink support: nothing to assert.
        }
        Files.createDirectories(target);

        assertThrows(WorkspaceException.class, () -> WorkspaceFork.copy(source, target));
        assertFalse(Files.exists(target.resolve("src/Real.java")));
    }
}
