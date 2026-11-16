package io.github.jasoncai0.agent.history.mapper;

import org.apache.ibatis.annotations.Update;

/** MyBatis-owned schema bootstrap. */
public interface SchemaMapper {
    @Update("""
            CREATE TABLE IF NOT EXISTS jade_sessions (
              run_id VARCHAR(64) NOT NULL PRIMARY KEY,
              snapshot LONGTEXT NOT NULL,
              updated_at BIGINT NOT NULL,
              KEY idx_jade_sessions_updated (updated_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createSessions();

    @Update("""
            CREATE TABLE IF NOT EXISTS jade_events (
              run_id VARCHAR(64) NOT NULL,
              seq BIGINT NOT NULL,
              ts BIGINT NOT NULL,
              event_type VARCHAR(64) NOT NULL,
              data LONGTEXT NOT NULL,
              compact_data LONGTEXT NOT NULL,
              PRIMARY KEY (run_id, seq)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """)
    void createEvents();
}
