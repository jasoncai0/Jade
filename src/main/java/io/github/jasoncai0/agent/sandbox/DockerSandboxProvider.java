package io.github.jasoncai0.agent.sandbox;

import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.CommandSandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Optional Docker container {@link SandboxProvider} discovered via {@link java.util.ServiceLoader}.
 * Inactive unless {@code AGENT_SANDBOX_BACKEND=docker} is explicitly configured.
 */
public final class DockerSandboxProvider implements SandboxProvider {
    private static final CommandSandbox NO_HOST_JAIL = CommandSandbox.off();

    @Override
    public String id() {
        return SandboxSettings.BACKEND_DOCKER;
    }

    @Override
    public boolean available() {
        return daemonAvailable();
    }

    @Override
    public String availabilityDetail() {
        if (!daemonAvailable()) {
            return "docker daemon unavailable";
        }
        String image = SandboxSettings.fromEnvironment().dockerImage();
        return imageAvailable(image)
                ? "ready (image=" + image + ")"
                : "daemon ready; image '" + image + "' not built yet (run ./scripts/build-sandbox-image.sh)";
    }

    public boolean daemonAvailable() {
        try {
            CommandRunner.CommandResult res = CommandRunner.execLocalProcess(
                    NO_HOST_JAIL, List.of("docker", "info", "--format", "{{.ServerVersion}}"),
                    Path.of("."), 4, Path.of("."), Map.of(), true);
            return res.success() && !res.stdout().isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean imageAvailable(String image) {
        if (image == null || image.isBlank()) {
            return false;
        }
        try {
            CommandRunner.CommandResult res = CommandRunner.execLocalProcess(
                    NO_HOST_JAIL, List.of("docker", "image", "inspect", image),
                    Path.of("."), 4, Path.of("."), Map.of(), true);
            return res.success();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public SandboxSession create(SandboxSpec spec) {
        if (!daemonAvailable()) {
            throw new IllegalStateException(
                    "AGENT_SANDBOX_BACKEND=docker is set, but the Docker daemon is not reachable. "
                            + "Start Docker or switch AGENT_SANDBOX_BACKEND=local (no silent fallback).");
        }
        String image = spec.settings().dockerImage();
        if (!imageAvailable(image)) {
            throw new IllegalStateException(
                    "AGENT_SANDBOX_BACKEND=docker is set, but image '" + image + "' was not found locally. "
                            + "Run `./scripts/build-sandbox-image.sh` or set AGENT_SANDBOX_DOCKER_IMAGE (no silent fallback).");
        }
        return new DockerSandboxSession(spec);
    }
}
