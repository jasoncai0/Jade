package io.github.jasoncai0.agent.sandbox;

import java.util.Locale;
import java.util.Set;

/** Immutable settings for pluggable execution sandboxes (default backend = local). */
public record SandboxSettings(
        String backend,
        String dockerImage,
        String dockerNetwork,
        String dockerMemory,
        String dockerCpus,
        int dockerPids,
        String previewPorts
) {
    public static final String BACKEND_LOCAL = "local";
    public static final String BACKEND_DOCKER = "docker";
    public static final String DEFAULT_DOCKER_IMAGE = "jade-sandbox:1";
    public static final String DEFAULT_DOCKER_NETWORK = "off";
    public static final String DEFAULT_DOCKER_MEMORY = "2g";
    public static final String DEFAULT_DOCKER_CPUS = "2";
    public static final int DEFAULT_DOCKER_PIDS = 256;
    public static final String DEFAULT_PREVIEW_PORTS = "8080";
    private static final Set<String> VALID_NETWORKS = Set.of("off", "on", "preview");

    public SandboxSettings {
        backend = backend == null || backend.isBlank()
                ? BACKEND_LOCAL
                : backend.trim().toLowerCase(Locale.ROOT);
        if (!BACKEND_LOCAL.equals(backend) && !BACKEND_DOCKER.equals(backend)) {
            throw new IllegalArgumentException(
                    "AGENT_SANDBOX_BACKEND must be 'local' or 'docker' (no silent 'auto'), got '" + backend + "'");
        }
        dockerImage = dockerImage == null || dockerImage.isBlank() ? DEFAULT_DOCKER_IMAGE : dockerImage.trim();
        dockerNetwork = dockerNetwork == null || dockerNetwork.isBlank()
                ? DEFAULT_DOCKER_NETWORK
                : dockerNetwork.trim().toLowerCase(Locale.ROOT);
        if (!VALID_NETWORKS.contains(dockerNetwork)) {
            throw new IllegalArgumentException(
                    "AGENT_SANDBOX_DOCKER_NETWORK must be off, on, or preview, got '" + dockerNetwork + "'");
        }
        dockerMemory = dockerMemory == null || dockerMemory.isBlank() ? DEFAULT_DOCKER_MEMORY : dockerMemory.trim();
        dockerCpus = dockerCpus == null || dockerCpus.isBlank() ? DEFAULT_DOCKER_CPUS : dockerCpus.trim();
        if (dockerPids < 16 || dockerPids > 65_536) {
            throw new IllegalArgumentException("AGENT_SANDBOX_DOCKER_PIDS must be 16..65536");
        }
        previewPorts = previewPorts == null || previewPorts.isBlank() ? DEFAULT_PREVIEW_PORTS : previewPorts.trim();
    }

    public static SandboxSettings defaults() {
        return new SandboxSettings(
                BACKEND_LOCAL,
                DEFAULT_DOCKER_IMAGE,
                DEFAULT_DOCKER_NETWORK,
                DEFAULT_DOCKER_MEMORY,
                DEFAULT_DOCKER_CPUS,
                DEFAULT_DOCKER_PIDS,
                DEFAULT_PREVIEW_PORTS
        );
    }

    public static SandboxSettings fromEnvironment() {
        String pidsRaw = System.getenv("AGENT_SANDBOX_DOCKER_PIDS");
        int pids = DEFAULT_DOCKER_PIDS;
        if (pidsRaw != null && !pidsRaw.isBlank()) {
            try {
                pids = Integer.parseInt(pidsRaw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("AGENT_SANDBOX_DOCKER_PIDS must be an integer", e);
            }
        }
        return new SandboxSettings(
                System.getenv("AGENT_SANDBOX_BACKEND"),
                System.getenv("AGENT_SANDBOX_DOCKER_IMAGE"),
                System.getenv("AGENT_SANDBOX_DOCKER_NETWORK"),
                System.getenv("AGENT_SANDBOX_DOCKER_MEMORY"),
                System.getenv("AGENT_SANDBOX_DOCKER_CPUS"),
                pids,
                System.getenv("AGENT_SANDBOX_DOCKER_PREVIEW_PORTS")
        );
    }

    public boolean isDocker() {
        return BACKEND_DOCKER.equals(backend);
    }
}
