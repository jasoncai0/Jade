package io.github.jasoncai0.agent.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {
    @TempDir
    Path temp;

    @Test
    void writesReadsAndListsFilesInsideTheJail() {
        Workspace workspace = Workspace.open(temp.resolve("ws"));

        int bytes = workspace.writeText("src/Main.java", "public class Main {}\n");
        workspace.writeText("README.md", "hello\n");

        assertEquals("public class Main {}\n".getBytes(StandardCharsets.UTF_8).length, bytes);
        assertTrue(workspace.exists("src/Main.java"));
        assertEquals("public class Main {}\n", workspace.readText("src/Main.java"));
        assertTrue(workspace.listFiles(50, 4).stream().anyMatch(entry -> entry.path().equals("src/Main.java")));
        assertTrue(workspace.tree(50, 4).contains("[d] src"));
    }

    @Test
    void rejectsTraversalAbsolutePathsAndSymlinkEscapes() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.root().resolve("escape.txt"), outside);

        assertThrows(WorkspaceException.class, () -> workspace.resolve("../outside.txt"));
        assertThrows(WorkspaceException.class, () -> workspace.resolve(outside.toString()));
        assertThrows(WorkspaceException.class, () -> workspace.readText("escape.txt"));
    }

    @Test
    void refusesBinaryAndOversizedText() throws Exception {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        Files.write(workspace.root().resolve("binary.bin"), new byte[]{1, 0, 2});
        Files.write(workspace.root().resolve("large.txt"), new byte[512 * 1024 + 1]);

        assertThrows(WorkspaceException.class, () -> workspace.readText("binary.bin"));
        assertThrows(WorkspaceException.class, () -> workspace.readText("large.txt"));
    }

    @Test
    void javaSourcesExcludeBuildAndDependencyDirectories() {
        Workspace workspace = Workspace.open(temp.resolve("ws"));
        workspace.writeText("src/Main.java", "public class Main {}\n");
        workspace.writeText("target/Stale.java", "class Stale {}\n");
        workspace.writeText(".agent-build/classes/Generated.java", "class Generated {}\n");
        workspace.writeText("notes.txt", "not java\n");

        List<Path> sources = workspace.javaSources();

        assertEquals(1, sources.size());
        assertTrue(workspace.relativize(sources.get(0)).endsWith("src/Main.java"));
        assertFalse(workspace.listFiles(100, 8).stream()
                .anyMatch(entry -> entry.path().startsWith("target")));
    }
}
