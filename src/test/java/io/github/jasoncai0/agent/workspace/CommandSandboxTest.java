package io.github.jasoncai0.agent.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSandboxTest {
    @TempDir
    Path temp;

    @Test
    void blocksWritesOutsideTheWorkspace() throws Exception {
        Path executable = Path.of("/usr/bin/sandbox-exec");
        Assumptions.assumeTrue(Files.isExecutable(executable), "macOS sandbox-exec is unavailable");

        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path outside = temp.resolve("outside.txt");
        CommandSandbox sandbox = CommandSandbox.create(
                CommandSandbox.Mode.AUTO, false, executable);
        CommandRunner runner = new CommandRunner(sandbox);

        CommandRunner.CommandResult result = runner.runShell(
                "echo inside > inside.txt; echo escaped > " + outside
                        + "; test ! -f " + outside,
                workspace, 10, workspace);

        assertTrue(result.success(), result.combinedOutput(4_000));
        assertTrue(Files.exists(workspace.resolve("inside.txt")), "workspace writes must be allowed");
        assertFalse(Files.exists(outside), "outside writes must be denied");
    }
}
