package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.workspace.WorkspaceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolOutputStoreTest {
    @TempDir
    Path temp;

    @Test
    void spillsLargeOutputAndKeepsHeadAndTail() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        String output = "FIRST LINE\n" + "x".repeat(39_980) + "\nLAST LINE: BUILD FAILED";

        ToolOutputRef ref = store.save("run-1", 7, "read_file", output);

        assertTrue(ref.spilled());
        assertEquals(output.length(), ref.originalChars());
        assertTrue(ref.relativePath().startsWith(".agent/tool-results/run-1/7-read_file"));
        assertEquals(output, Files.readString(root.resolve(ref.relativePath()), StandardCharsets.UTF_8));

        String inline = store.inline(output, ref);
        assertTrue(inline.startsWith("FIRST LINE\n"));
        assertTrue(inline.endsWith("LAST LINE: BUILD FAILED"));
        assertTrue(inline.contains("[output truncated: " + output.length() + " chars"));
        assertTrue(inline.contains(ref.relativePath()));
        assertTrue(inline.contains("read with read_tool_output"));
    }

    @Test
    void sameSequenceAndToolWithDifferentCallIdsDoNotCollide() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        String first = "FIRST " + "a".repeat(31_000);
        String second = "SECOND " + "b".repeat(31_000);

        ToolOutputRef a = store.save("run-1", 7, "read_file", "call-a", first);
        ToolOutputRef b = store.save("run-1", 7, "read_file", "call-b", second);

        assertTrue(a.spilled());
        assertTrue(b.spilled());
        assertTrue(!a.relativePath().equals(b.relativePath()));
        assertEquals(first, Files.readString(root.resolve(a.relativePath()), StandardCharsets.UTF_8));
        assertEquals(second, Files.readString(root.resolve(b.relativePath()), StandardCharsets.UTF_8));
    }

    @Test
    void leavesBoundarySizedOutputAlone() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        String output = "a".repeat(ToolOutputStore.MAX_INLINE_CHARS);

        ToolOutputRef ref = store.save("run-1", 1, "read_file", output);

        assertFalse(ref.spilled());
        assertEquals(output, store.inline(output, ref));
        assertFalse(Files.exists(root.resolve(".agent/tool-results/run-1/1-read_file.txt")));
    }

    @Test
    void sanitizesToolNameAndRunId() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        String output = "b".repeat(31_000);

        ToolOutputRef ref = store.save("../run", 3, "../../evil name", output);

        String fileName = Path.of(ref.relativePath()).getFileName().toString();
        assertTrue(fileName.startsWith("3-"));
        assertFalse(fileName.contains("/"));
        assertTrue(fileName.matches("[0-9]+-[A-Za-z0-9_.-]+\\.txt"));
        assertTrue(ref.relativePath().startsWith(".agent/tool-results/"));
        assertFalse(ref.relativePath().contains("../"));
    }

    @Test
    void readsOnlyTheRequestedCharacterWindow() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        StringBuilder output = new StringBuilder(40_000);
        for (int i = 0; i < 40_000; i++) {
            output.append((char) ('a' + (i % 26)));
        }
        ToolOutputRef ref = store.save("run-1", 9, "search_text", output.toString());

        String window = store.read(ref.relativePath(), 30_000, 1_000);

        assertEquals(output.substring(30_000, 31_000), window);
        assertEquals(40_000, store.totalChars(ref.relativePath()));
    }

    @Test
    void returnsEmptyWhenStartIsBeyondEnd() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        ToolOutputRef ref = store.save("run-1", 2, "read_file", "c".repeat(31_000));

        assertEquals("", store.read(ref.relativePath(), 50_000, 100));
        assertEquals(31_000, store.totalChars(ref.relativePath()));
    }

    @Test
    void rejectsPathsOutsideTheSpillRoot() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        Path outside = temp.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Path spillDir = Files.createDirectories(root.resolve(".agent/tool-results/run-1"));
        Files.createSymbolicLink(spillDir.resolve("link.txt"), outside);

        assertThrows(WorkspaceException.class, () -> store.read("../outside.txt", 0, 10));
        assertThrows(WorkspaceException.class, () -> store.read(outside.toString(), 0, 10));
        assertThrows(WorkspaceException.class, () -> store.read(".agent/tool-results/run-1/../../x.txt", 0, 10));
        assertThrows(WorkspaceException.class, () -> store.read(".agent/tool-results/run-1/link.txt", 0, 10));
        assertThrows(WorkspaceException.class, () -> store.read(".agent/tool-results/run-1", 0, 10));
        assertThrows(WorkspaceException.class, () -> store.read(".agent/tool-results/run-1/a\0.txt", 0, 10));
    }

    @Test
    void spillFailureIsReportedAsWorkspaceException() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        Files.writeString(root.resolve(".agent"), "not a directory");
        ToolOutputStore store = new ToolOutputStore(root);

        assertThrows(WorkspaceException.class,
                () -> store.save("run-1", 1, "read_file", "d".repeat(31_000)));
    }

    @Test
    void spillFileIsOwnerOnlyWhenPosixIsAvailable() throws IOException {
        Path root = Files.createDirectories(temp.resolve("ws"));
        ToolOutputStore store = new ToolOutputStore(root);
        ToolOutputRef ref = store.save("run-1", 1, "read_file", "e".repeat(31_000));

        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(root.resolve(ref.relativePath()));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
    }
}
