package io.github.jasoncai0.agent.memory;

import java.nio.file.Path;
import java.util.List;

/** Disabled or unavailable memory. It never invents memories and never fails a run. */
public final class NoopMemoryService implements MemoryService {
    private final MemoryMode mode;
    private final MemoryHealth health;
    private final MemorySettings settings;
    private final Path workspacesRoot;

    public NoopMemoryService(MemoryMode mode, MemoryHealth health) {
        this(mode, health, MemorySettings.defaults(), null);
    }

    public NoopMemoryService(MemoryMode mode, MemoryHealth health,
                             MemorySettings settings, Path workspacesRoot) {
        this.mode = mode == null ? MemoryMode.OFF : mode;
        this.health = health == null ? MemoryHealth.disabled(this.mode.id(), "memory disabled") : health;
        this.settings = settings == null ? MemorySettings.defaults() : settings;
        this.workspacesRoot = workspacesRoot;
    }

    public static NoopMemoryService off() {
        return new NoopMemoryService(MemoryMode.OFF,
                MemoryHealth.disabled("off", "memory disabled"));
    }

    @Override
    public MemoryMode mode() {
        return mode;
    }

    @Override
    public MemoryHealth health() {
        return health;
    }

    @Override
    public MemoryScope scope(Path workspace, String sessionId) {
        return MemoryPolicy.scope(settings, workspace, workspacesRoot, sessionId);
    }

    @Override
    public List<MemoryItem> recall(String query, MemoryScope scope, int limit) {
        return List.of();
    }

    @Override
    public String remember(MemoryCandidate candidate, MemoryScope scope) {
        return null;
    }
}
