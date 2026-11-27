package io.github.jasoncai0.agent.history;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.RunSnapshot;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.config.MysqlSettings;
import io.github.jasoncai0.agent.history.mapper.EventMapper;
import io.github.jasoncai0.agent.history.mapper.EventRow;
import io.github.jasoncai0.agent.history.mapper.SchemaMapper;
import io.github.jasoncai0.agent.history.mapper.SessionMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MySQL-backed event log implemented through MyBatis mappers.
 *
 * <p>Each public call uses a pooled SqlSession. The mapper XML owns all SQL;
 * this class only performs JSON projection and domain conversion.</p>
 */
public final class MySqlEventStore implements EventStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LLM_PREVIEW = 2000;
    private static final int ARGS_PREVIEW = 4000;
    private static final int OUTPUT_PREVIEW = 8000;

    private final MysqlSettings settings;
    private final PooledDataSource dataSource;
    private final SqlSessionFactory sessionFactory;

    public MySqlEventStore(MysqlSettings settings) {
        this.settings = settings;
        this.dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                settings.url(),
                settings.user(),
                settings.password()
        );
        dataSource.setPoolMaximumActiveConnections(8);
        dataSource.setPoolMaximumIdleConnections(4);
        dataSource.setPoolPingEnabled(true);
        dataSource.setPoolPingQuery("SELECT 1");
        dataSource.setPoolPingConnectionsNotUsedFor(30_000);

        Configuration configuration = new Configuration(
                new Environment("jade", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(SchemaMapper.class);
        configuration.addMapper(SessionMapper.class);
        configuration.addMapper(EventMapper.class);
        this.sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        schema();
    }

    private void schema() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            SchemaMapper mapper = session.getMapper(SchemaMapper.class);
            mapper.createSessions();
            mapper.createEvents();
        } catch (RuntimeException e) {
            dataSource.forceCloseAll();
            throw new IllegalStateException("Cannot initialize Jade MySQL tables at "
                    + settings.url() + " (is `docker compose up -d` running?)", e);
        }
    }

    @Override
    public void append(String runId, AgentEvent event) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            session.getMapper(EventMapper.class).upsert(
                    runId,
                    event.seq(),
                    event.timestamp(),
                    event.type(),
                    JSON.writeValueAsString(event.data()),
                    JSON.writeValueAsString(compact(event.type(), event.data()))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Cannot append event " + runId + "#" + event.seq(), e);
        }
    }

    @Override
    public Page page(String runId, long before, int requestedLimit, boolean compact) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        long cursor = before > 0 ? before : Long.MAX_VALUE;
        try (SqlSession session = sessionFactory.openSession()) {
            EventMapper mapper = session.getMapper(EventMapper.class);
            List<EventRow> rows = compact
                    ? mapper.pageCompact(runId, cursor, limit)
                    : mapper.pageFull(runId, cursor, limit);
            Collections.reverse(rows);
            List<AgentEvent> events = new ArrayList<>(rows.size());
            for (EventRow row : rows) {
                events.add(toEvent(row));
            }
            long lastSeq = mapper.lastSeq(runId);
            long count = mapper.eventCount(runId);
            long nextBefore = events.isEmpty() ? before : events.get(0).seq();
            boolean hasMore = !events.isEmpty() && mapper.existsBefore(runId, nextBefore) != null;
            return new Page(events, nextBefore, hasMore, lastSeq, count);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot read event page for " + runId, e);
        }
    }

    @Override
    public Optional<AgentEvent> event(String runId, long seq) {
        try (SqlSession session = sessionFactory.openSession()) {
            EventRow row = session.getMapper(EventMapper.class).find(runId, seq);
            return Optional.ofNullable(row).map(this::toEvent);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot read event " + runId + "#" + seq, e);
        }
    }

    private AgentEvent toEvent(EventRow row) {
        try {
            JsonNode node = JSON.readTree(row.getPayload());
            Map<String, Object> data = JSON.convertValue(
                    node, new TypeReference<LinkedHashMap<String, Object>>() {});
            return new AgentEvent(row.getSeq(), row.getTs(), row.getEventType(), data);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse persisted event payload", e);
        }
    }

    @Override
    public long lastSeq(String runId) {
        try (SqlSession session = sessionFactory.openSession()) {
            return session.getMapper(EventMapper.class).lastSeq(runId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot read last sequence for " + runId, e);
        }
    }

    @Override
    public long eventCount(String runId) {
        try (SqlSession session = sessionFactory.openSession()) {
            return session.getMapper(EventMapper.class).eventCount(runId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot count events for " + runId, e);
        }
    }

    @Override
    public void saveSnapshot(RunSnapshot snapshot) {
        try (SqlSession session = sessionFactory.openSession(true)) {
            upsertSnapshot(session, snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save session " + snapshot.id(), e);
        }
    }

    private void upsertSnapshot(SqlSession session, RunSnapshot snapshot) throws Exception {
        session.getMapper(SessionMapper.class).upsert(
                snapshot.id(), JSON.writeValueAsString(snapshot), System.currentTimeMillis());
    }

    @Override
    public List<RunSnapshot> snapshots(int limit) {
        List<RunSnapshot> result = new ArrayList<>();
        try (SqlSession session = sessionFactory.openSession()) {
            List<String> rows = session.getMapper(SessionMapper.class)
                    .listSnapshots(Math.max(1, Math.min(limit, 500)));
            for (String row : rows) {
                result.add(JSON.readValue(row, RunSnapshot.class));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list sessions", e);
        }
        return result;
    }

    @Override
    public Optional<RunSnapshot> snapshot(String runId) {
        try (SqlSession session = sessionFactory.openSession()) {
            String row = session.getMapper(SessionMapper.class).findSnapshot(runId);
            return row == null ? Optional.empty() : Optional.of(JSON.readValue(row, RunSnapshot.class));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read session " + runId, e);
        }
    }

    @Override
    public boolean isEmpty() {
        try (SqlSession session = sessionFactory.openSession()) {
            return session.getMapper(SessionMapper.class).countSessions() == 0;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot check session store", e);
        }
    }

    @Override
    public void importRun(RunSnapshot snapshot, List<AgentEvent> events) {
        try (SqlSession session = sessionFactory.openSession(ExecutorType.BATCH, false)) {
            upsertSnapshot(session, snapshot);
            EventMapper mapper = session.getMapper(EventMapper.class);
            for (AgentEvent event : events) {
                mapper.upsert(
                        snapshot.id(),
                        event.seq(),
                        event.timestamp(),
                        event.type(),
                        JSON.writeValueAsString(event.data()),
                        JSON.writeValueAsString(compact(event.type(), event.data()))
                );
            }
            session.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot import session " + snapshot.id(), e);
        }
    }

    @Override
    public int deleteRuns(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return 0;
        }
        List<String> clean = runIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (clean.isEmpty()) {
            return 0;
        }
        int deletedSessions = 0;
        try (SqlSession session = sessionFactory.openSession(false)) {
            EventMapper eventMapper = session.getMapper(EventMapper.class);
            SessionMapper sessionMapper = session.getMapper(SessionMapper.class);
            for (int start = 0; start < clean.size(); start += 500) {
                List<String> chunk = clean.subList(start, Math.min(clean.size(), start + 500));
                eventMapper.deleteByRunIds(chunk);
                deletedSessions += sessionMapper.deleteByRunIds(chunk);
            }
            session.commit();
            return deletedSessions;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot delete pruned sessions", e);
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
        return text.length() <= max ? text : text.substring(0, max)
                + "\n… [truncated " + (text.length() - max) + " chars]";
    }

    @Override
    public void close() {
        dataSource.forceCloseAll();
        Log.info("mysql.pool.closed", Map.of("url", settings.url()));
    }
}
