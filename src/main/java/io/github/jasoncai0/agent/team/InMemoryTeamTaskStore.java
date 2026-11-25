package io.github.jasoncai0.agent.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryTeamTaskStore implements TeamTaskStore {
    private final Map<String, TeamState> teams = new LinkedHashMap<>();

    private record TeamState(boolean enabled, long[] next, Map<String, TeamTask> tasks) {
    }

    @Override
    public synchronized void ensureTeam(String rootRunId, boolean defaultEnabled) {
        teams.computeIfAbsent(rootRunId, ignored -> new TeamState(defaultEnabled, new long[]{1}, new LinkedHashMap<>()));
    }

    @Override
    public synchronized boolean enabled(String rootRunId) {
        TeamState state = teams.get(rootRunId);
        return state != null && state.enabled();
    }

    @Override
    public synchronized void setEnabled(String rootRunId, boolean enabled) {
        TeamState old = teams.get(rootRunId);
        if (old == null) {
            throw new IllegalArgumentException("unknown team root: " + rootRunId);
        }
        teams.put(rootRunId, new TeamState(enabled, old.next(), old.tasks()));
    }

    @Override
    public synchronized TeamTask create(String rootRunId, String subject, String description,
                                        List<String> blockedBy, List<String> writeScopes) {
        TeamState state = require(rootRunId);
        long now = System.currentTimeMillis();
        String id = "task-" + state.next()[0];
        state.next()[0]++;
        TeamTask task = new TeamTask(id, 1, subject, description, TeamTaskStatus.PENDING,
                null, null, blockedBy, writeScopes, now, now);
        state.tasks().put(id, task);
        return task;
    }

    @Override
    public synchronized Optional<TeamTask> find(String rootRunId, String taskId) {
        TeamState state = teams.get(rootRunId);
        return state == null ? Optional.empty() : Optional.ofNullable(state.tasks().get(taskId));
    }

    @Override
    public synchronized List<TeamTask> list(String rootRunId) {
        TeamState state = teams.get(rootRunId);
        if (state == null) {
            return List.of();
        }
        return state.tasks().values().stream()
                .sorted(Comparator.comparingLong((TeamTask t) -> Long.parseLong(t.id().substring(5))))
                .toList();
    }

    @Override
    public synchronized TeamTask save(String rootRunId, TeamTask expected, TeamTask next) {
        TeamState state = require(rootRunId);
        TeamTask current = state.tasks().get(expected.id());
        if (current == null || current.revision() != expected.revision()) {
            return null;
        }
        TeamTask saved = next.withRevision(current.revision() + 1, System.currentTimeMillis());
        state.tasks().put(saved.id(), saved);
        return saved;
    }

    private TeamState require(String rootRunId) {
        TeamState state = teams.get(rootRunId);
        if (state == null) {
            throw new IllegalArgumentException("unknown team root: " + rootRunId);
        }
        return state;
    }
}
