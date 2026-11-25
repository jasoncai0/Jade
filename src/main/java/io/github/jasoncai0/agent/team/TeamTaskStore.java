package io.github.jasoncai0.agent.team;

import java.util.List;
import java.util.Optional;

public interface TeamTaskStore extends AutoCloseable {
    void ensureTeam(String rootRunId, boolean defaultEnabled);

    boolean enabled(String rootRunId);

    void setEnabled(String rootRunId, boolean enabled);

    TeamTask create(String rootRunId, String subject, String description,
                    List<String> blockedBy, List<String> writeScopes);

    Optional<TeamTask> find(String rootRunId, String taskId);

    List<TeamTask> list(String rootRunId);

    /**
     * Compare-and-set save. Returns null when the task revision no longer matches.
     */
    TeamTask save(String rootRunId, TeamTask expected, TeamTask next);

    @Override
    default void close() {
    }
}
