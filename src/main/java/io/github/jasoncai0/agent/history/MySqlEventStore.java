package io.github.jasoncai0.agent.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.RunSnapshot;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.config.MysqlSettings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MySQL-backed event log. The normal path writes full payloads plus a compact
 * projection used by the Live Trace. Full LLM/tool payloads are fetched only
 * when a user explicitly opens an old request.
 */
public final class MySqlEventStore implements EventStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LLM_PREVIEW = 2000;
    private static final int ARGS_PREVIEW = 4000;
    private static final int OUTPUT_PREVIEW = 8000;

    private final MysqlSettings settings;
    private Connection connection;

    public MySqlEventStore(MysqlSettings settings) {
        this.settings = settings;
        connect();
        schema();
    }

    private synchronized void connect() {
        try {
            connection = DriverManager.getConnection(settings.url(), settings.user(), settings.password());
            try (Statement st = connection.createStatement()) {
                st.execute("SET NAMES utf8mb4");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot connect to MySQL at " + settings.url()
                    + " (is `docker compose up -d` running?)", e);
        }
    }

    private synchronized Connection connection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                connect();
            }
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL connection is not usable", e);
        }
    }

    private void schema() {
        synchronized (this) {
            try (Statement st = connection().createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS jade_sessions (
                          run_id VARCHAR(64) NOT NULL PRIMARY KEY,
                          snapshot LONGTEXT NOT NULL,
                          updated_at BIGINT NOT NULL,
                          KEY idx_jade_sessions_updated (updated_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS jade_events (
                          run_id VARCHAR(64) NOT NULL,
                          seq BIGINT NOT NULL,
                          ts BIGINT NOT NULL,
                          event_type VARCHAR(64) NOT NULL,
                          data LONGTEXT NOT NULL,
                          compact_data LONGTEXT NOT NULL,
                          PRIMARY KEY (run_id, seq)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """);
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot create Jade MySQL tables", e);
            }
        }
    }

    @Override
    public synchronized void append(String runId, AgentEvent event) {
        try {
            String full = JSON.writeValueAsString(event.data());
            String compact = JSON.writeValueAsString(compact(event.type(), event.data()));
            try (PreparedStatement ps = connection().prepareStatement("""
                    INSERT INTO jade_events(run_id, seq, ts, event_type, data, compact_data)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE ts=VALUES(ts), event_type=VALUES(event_type),
                      data=VALUES(data), compact_data=VALUES(compact_data)
                    """)) {
                ps.setString(1, runId);
                ps.setLong(2, event.seq());
                ps.setLong(3, event.timestamp());
                ps.setString(4, event.type());
                ps.setString(5, full);
                ps.setString(6, compact);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot append event " + runId + "#" + event.seq(), e);
        }
    }

    @Override
    public synchronized Page page(String runId, long before, int requestedLimit, boolean compact) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        String column = compact ? "compact_data" : "data";
        String where = before > 0 ? " AND seq < ?" : "";
        List<AgentEvent> events = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement("""
                SELECT seq, ts, event_type, %s AS payload FROM jade_events
                WHERE run_id = ?%s ORDER BY seq DESC LIMIT ?
                """.formatted(column, where))) {
            int i = 1;
            ps.setString(i++, runId);
            if (before > 0) ps.setLong(i++, before);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(0, event(rs.getLong("seq"), rs.getLong("ts"),
                            rs.getString("event_type"), rs.getString("payload")));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read event page for " + runId, e);
        }
        long lastSeq = lastSeq(runId);
        long count = eventCount(runId);
        long nextBefore = events.isEmpty() ? before : events.get(0).seq();
        boolean hasMore = false;
        if (!events.isEmpty()) {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT 1 FROM jade_events WHERE run_id = ? AND seq < ? LIMIT 1")) {
                ps.setString(1, runId);
                ps.setLong(2, nextBefore);
                try (ResultSet rs = ps.executeQuery()) {
                    hasMore = rs.next();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot check history page for " + runId, e);
            }
        }
        return new Page(events, nextBefore, hasMore, lastSeq, count);
    }

    @Override
    public synchronized Optional<AgentEvent> event(String runId, long seq) {
        try (PreparedStatement ps = connection().prepareStatement("""
                SELECT seq, ts, event_type, data FROM jade_events WHERE run_id = ? AND seq = ?
                """)) {
            ps.setString(1, runId);
            ps.setLong(2, seq);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(event(rs.getLong("seq"), rs.getLong("ts"),
                        rs.getString("event_type"), rs.getString("data")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read event " + runId + "#" + seq, e);
        }
    }

    private AgentEvent event(long seq, long ts, String type, String payload) throws Exception {
        JsonNode node = JSON.readTree(payload);
        Map<String, Object> data = JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
        return new AgentEvent(seq, ts, type, data);
    }

    @Override
    public synchronized long lastSeq(String runId) {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT COALESCE(MAX(seq), 0) FROM jade_events WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read last sequence for " + runId, e);
        }
    }

    @Override
    public synchronized long eventCount(String runId) {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT COUNT(*) FROM jade_events WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count events for " + runId, e);
        }
    }

    @Override
    public synchronized void saveSnapshot(RunSnapshot snapshot) {
        try (PreparedStatement ps = connection().prepareStatement("""
                INSERT INTO jade_sessions(run_id, snapshot, updated_at) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE snapshot=VALUES(snapshot), updated_at=VALUES(updated_at)
                """)) {
            ps.setString(1, snapshot.id());
            ps.setString(2, JSON.writeValueAsString(snapshot));
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save session " + snapshot.id(), e);
        }
    }

    @Override
    public synchronized List<RunSnapshot> snapshots(int limit) {
        List<RunSnapshot> result = new ArrayList<>();
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT snapshot FROM jade_sessions ORDER BY updated_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(JSON.readValue(rs.getString(1), RunSnapshot.class));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list sessions", e);
        }
        return result;
    }

    @Override
    public synchronized Optional<RunSnapshot> snapshot(String runId) {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT snapshot FROM jade_sessions WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(JSON.readValue(rs.getString(1), RunSnapshot.class));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read session " + runId, e);
        }
    }

    @Override
    public synchronized boolean isEmpty() {
        try (Statement st = connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM jade_sessions LIMIT 1")) {
            return !rs.next();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check session store", e);
        }
    }

    @Override
    public synchronized void importRun(RunSnapshot snapshot, List<AgentEvent> events) {
        Connection c = connection();
        try {
            c.setAutoCommit(false);
            saveSnapshot(snapshot);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO jade_events(run_id, seq, ts, event_type, data, compact_data)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE ts=VALUES(ts), event_type=VALUES(event_type),
                      data=VALUES(data), compact_data=VALUES(compact_data)
                    """)) {
                int n = 0;
                for (AgentEvent event : events) {
                    ps.setString(1, snapshot.id());
                    ps.setLong(2, event.seq());
                    ps.setLong(3, event.timestamp());
                    ps.setString(4, event.type());
                    ps.setString(5, JSON.writeValueAsString(event.data()));
                    ps.setString(6, JSON.writeValueAsString(compact(event.type(), event.data())));
                    ps.addBatch();
                    if (++n % 200 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (Exception e) {
            try { c.rollback(); } catch (SQLException ignored) {}
            throw new IllegalStateException("Cannot import session " + snapshot.id(), e);
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    private Map<String, Object> compact(String type, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>(data);
        if ("llm.request".equals(type) && out.get("body") instanceof String body) {
            out.remove("body");
            out.put("bodyPreview", truncate(body, LLM_PREVIEW));
            out.put("bodySize", body.length());
            out.put("bodyOmitted", true);
        }
        if ("tool.start".equals(type) && out.get("arguments") instanceof String args) {
            out.put("arguments", truncate(args, ARGS_PREVIEW));
        }
        if ("tool.end".equals(type) && out.get("output") instanceof String output
                && output.length() > OUTPUT_PREVIEW) {
            out.put("output", truncate(output, OUTPUT_PREVIEW));
            out.put("outputOmitted", true);
            out.put("outputSize", output.length());
        }
        return out;
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n… [truncated " + (text.length() - max) + " chars]";
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            Log.warn("mysql.close.failed", Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
