package io.github.jasoncai0.agent.sandbox;

import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.CommandSandbox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxProviderTest {
    @TempDir
    Path temp;

    @Test
    void discoversLocalAndDockerProvidersAndDefaultsToLocal() {
        SandboxRegistry registry = new SandboxRegistry();
        assertTrue(registry.providerIds().contains("local"));
        assertTrue(registry.providerIds().contains("docker"));

        SandboxSettings defaults = SandboxSettings.defaults();
        assertEquals("local", defaults.backend());
        registry.validateStartup(defaults);

        try (SandboxSession local = registry.openSession("run-local", temp, defaults)) {
            assertEquals("local", local.info().backend());
            assertFalse(local.info().containerIsolated());
            CommandRunner.CommandResult res = local.execShell("echo hello-local", temp, 10, temp, false);
            assertTrue(res.success());
            assertTrue(res.stdout().contains("hello-local"));
        }
    }

    @Test
    void rejectsSilentAutoBackendAndFailsClosedWhenDockerImageMissing() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxSettings(
                "auto", "jade-sandbox:1", "off", "2g", "2", 256, "8080"));

        SandboxRegistry registry = new SandboxRegistry();
        SandboxSettings missingImage = new SandboxSettings(
                "docker", "jade-nonexistent-sandbox-image:never-built-99999",
                "off", "2g", "2", 256, "8080");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.validateStartup(missingImage));
        assertTrue(error.getMessage().contains("AGENT_SANDBOX_BACKEND=docker"));
    }

    @Test
    void buildsHardenedDockerRunArgumentsPerSpec() throws Exception {
        Path ws = Files.createDirectories(temp.resolve("ws"));
        SandboxSettings settings = new SandboxSettings(
                "docker", "jade-sandbox:1", "off", "2g", "2", 256, "8080");
        DockerSandboxSession session = new DockerSandboxSession(new SandboxSpec("run-abc123", ws, settings));
        List<String> cmd = session.buildDockerRunCommand(ws.toRealPath());

        assertTrue(cmd.contains("--read-only"));
        assertTrue(cmd.contains("/tmp:rw,noexec,nosuid,size=256m"));
        assertTrue(cmd.contains("--cap-drop=ALL"));
        assertTrue(cmd.contains("--security-opt=no-new-privileges"));
        assertTrue(cmd.contains("--pids-limit=256"));
        assertTrue(cmd.contains("--memory=2g"));
        assertTrue(cmd.contains("--cpus=2"));
        assertTrue(cmd.contains("10001:10001"));
        int netIdx = cmd.indexOf("--network");
        assertTrue(netIdx >= 0 && "none".equals(cmd.get(netIdx + 1)));
        assertTrue(cmd.stream().anyMatch(arg -> arg.endsWith(":/workspace:rw")));
    }

    @Test
    void enforcesRealDockerContainerIsolationWhenDockerImageAvailable() throws Exception {
        DockerSandboxProvider provider = new DockerSandboxProvider();
        Assumptions.assumeTrue(provider.daemonAvailable(), "Docker daemon is not running");
        // Reuse any locally available Linux image with /bin/sh (jade-sandbox:1 or mysql:8.4)
        String testImage = provider.imageAvailable("jade-sandbox:1") ? "jade-sandbox:1"
                : provider.imageAvailable("alpine:latest") ? "alpine:latest" : null;
        Assumptions.assumeTrue(testImage != null, "No local Linux test image found");

        // TempDir on macOS is under /var/folders (symlink to /private/var/folders), shared with Docker Desktop
        Path ws = Files.createDirectories(temp.resolve("docker-ws"));
        Files.writeString(ws.resolve("seed.txt"), "from-host\n");
        SandboxSettings settings = new SandboxSettings("docker", testImage, "off", "512m", "1", 128, "8080");

        try (SandboxSession session = provider.create(new SandboxSpec("it-" + System.nanoTime(), ws, settings))) {
            CommandRunner runner = new CommandRunner(session);
            assertTrue(runner.isContainer());

            // 1) Runs as UID 10001 and sees bind-mounted /workspace
            CommandRunner.CommandResult idRes = runner.runShell("id -u && cat /workspace/seed.txt", ws, 15, ws, false);
            assertTrue(idRes.success(), idRes.combinedOutput(1000));
            assertTrue(idRes.stdout().contains("10001"), idRes.stdout());
            assertTrue(idRes.stdout().contains("from-host"), idRes.stdout());

            // 2) Writes inside /workspace persist back to host workspace
            CommandRunner.CommandResult writeWs = runner.runShell("echo container-wrote > /workspace/out.txt", ws, 15, ws, false);
            assertTrue(writeWs.success(), writeWs.combinedOutput(1000));
            assertEquals("container-wrote\n", Files.readString(ws.resolve("out.txt")));

            // 3) Read-only rootfs blocks writing outside /workspace and /tmp
            CommandRunner.CommandResult writeRoot = runner.runShell("touch /etc/escape_probe", ws, 15, ws, false);
            assertFalse(writeRoot.success(), "Read-only rootfs must reject writes to /etc");

            // 4) Sensitive env keys are stripped even if passed in extraEnvironment
            CommandRunner.CommandResult envCheck = runner.run(
                    List.of("/bin/sh", "-c", "printf '%s' \"${DEEPSEEK_API_KEY:-EMPTY}\""),
                    ws, 15, ws, Map.of("DEEPSEEK_API_KEY", "sk-should-never-enter-container"), false);
            assertTrue(envCheck.success());
            assertEquals("EMPTY", envCheck.stdout().strip());
        }
    }
}
