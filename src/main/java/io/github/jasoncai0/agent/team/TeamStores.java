package io.github.jasoncai0.agent.team;

import io.github.jasoncai0.agent.config.MysqlSettings;
import io.github.jasoncai0.agent.config.Log;

public final class TeamStores {
    private TeamStores() {
    }

    public static TeamTaskStore create(MysqlSettings settings) {
        if (settings == null || settings.url() == null || settings.url().isBlank()) {
            return new InMemoryTeamTaskStore();
        }
        try {
            return new MySqlTeamTaskStore(settings);
        } catch (RuntimeException e) {
            Log.warn("team.store_fallback", java.util.Map.of("error", String.valueOf(e.getMessage())));
            return new InMemoryTeamTaskStore();
        }
    }
}
