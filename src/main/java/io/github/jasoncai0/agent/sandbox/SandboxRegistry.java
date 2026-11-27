package io.github.jasoncai0.agent.sandbox;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Discovers {@link SandboxProvider} implementations via {@link ServiceLoader} while
 * guaranteeing {@link LocalSandboxProvider} is always registered as the default.
 * Fail-closed: if a non-local backend is explicitly configured and missing/unavailable,
 * session creation fails explicitly without falling back to host execution.
 */
public final class SandboxRegistry {
    private final Map<String, SandboxProvider> providers = new LinkedHashMap<>();

    public SandboxRegistry() {
        this(ServiceLoader.load(SandboxProvider.class));
    }

    public SandboxRegistry(Iterable<SandboxProvider> discovered) {
        register(new LocalSandboxProvider());
        if (discovered != null) {
            for (SandboxProvider provider : discovered) {
                if (provider != null && provider.id() != null && !provider.id().isBlank()) {
                    register(provider);
                }
            }
        }
    }

    public SandboxRegistry register(SandboxProvider provider) {
        providers.put(provider.id().trim().toLowerCase(), provider);
        return this;
    }

    public List<String> providerIds() {
        return List.copyOf(providers.keySet());
    }

    public SandboxProvider requireProvider(String backend) {
        String id = backend == null || backend.isBlank()
                ? SandboxSettings.BACKEND_LOCAL
                : backend.trim().toLowerCase();
        SandboxProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalStateException(
                    "AGENT_SANDBOX_BACKEND=" + id + " requested, but no SandboxProvider '" + id
                            + "' is installed (registered: " + providers.keySet() + "). Refusing silent fallback.");
        }
        return provider;
    }

    public void validateStartup(SandboxSettings settings) {
        SandboxSettings safe = settings == null ? SandboxSettings.defaults() : settings;
        if (!safe.isDocker()) {
            return;
        }
        SandboxProvider provider = requireProvider(safe.backend());
        if (!provider.available()) {
            throw new IllegalStateException(
                    "AGENT_SANDBOX_BACKEND=" + safe.backend() + " is unavailable: "
                            + provider.availabilityDetail() + " (refusing silent fallback to local execution)");
        }
        if (provider instanceof DockerSandboxProvider docker && !docker.imageAvailable(safe.dockerImage())) {
            throw new IllegalStateException(
                    "AGENT_SANDBOX_BACKEND=docker requires local image '" + safe.dockerImage()
                            + "'. Build it with `./scripts/build-sandbox-image.sh` or change AGENT_SANDBOX_DOCKER_IMAGE.");
        }
    }

    public SandboxSession openSession(String sessionId, Path workspaceRoot, SandboxSettings settings) {
        SandboxSettings safe = settings == null ? SandboxSettings.defaults() : settings;
        SandboxProvider provider = requireProvider(safe.backend());
        return provider.create(new SandboxSpec(sessionId, workspaceRoot, safe));
    }

    public Map<String, Object> summary(SandboxSettings settings) {
        SandboxSettings safe = settings == null ? SandboxSettings.defaults() : settings;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", safe.backend());
        out.put("providers", providerIds());
        SandboxProvider docker = providers.get(SandboxSettings.BACKEND_DOCKER);
        out.put("dockerPluginLoaded", docker != null);
        out.put("dockerAvailable", docker != null && docker.available());
        out.put("dockerDetail", docker == null ? "plugin not loaded" : docker.availabilityDetail());
        if (safe.isDocker()) {
            out.put("dockerImage", safe.dockerImage());
            out.put("dockerNetwork", safe.dockerNetwork());
            out.put("dockerMemory", safe.dockerMemory());
            out.put("dockerCpus", safe.dockerCpus());
            out.put("dockerPids", safe.dockerPids());
        }
        return out;
    }
}
