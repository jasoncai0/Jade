package io.github.jasoncai0.agent.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.MysqlSettings;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.util.List;
import java.util.Optional;

public final class MySqlTeamTaskStore implements TeamTaskStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SqlSessionFactory factory;
    private final PooledDataSource dataSource;

    public MySqlTeamTaskStore(MysqlSettings settings) {
        if (settings == null || settings.url() == null || settings.url().isBlank()) {
            throw new IllegalArgumentException("MySQL settings are required for team storage");
        }
        this.dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                settings.url(), settings.user(), settings.password());
        Configuration configuration = new Configuration(new Environment(
                "team", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(TeamMapper.class);
        this.factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true)) {
            TeamMapper mapper = session.getMapper(TeamMapper.class);
            mapper.createTeams();
            mapper.createTasks();
        }
    }

    @Override
    public synchronized void ensureTeam(String rootRunId, boolean defaultEnabled) {
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(TeamMapper.class).ensureTeam(rootRunId, defaultEnabled, System.currentTimeMillis());
        }
    }

    @Override
    public synchronized boolean enabled(String rootRunId) {
        try (SqlSession session = factory.openSession(true)) {
            Boolean value = session.getMapper(TeamMapper.class).enabled(rootRunId);
            return Boolean.TRUE.equals(value);
        }
    }

    @Override
    public synchronized void setEnabled(String rootRunId, boolean enabled) {
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(TeamMapper.class).setEnabled(rootRunId, enabled, System.currentTimeMillis());
        }
    }

    @Override
    public synchronized TeamTask create(String rootRunId, String subject, String description,
                                        List<String> blockedBy, List<String> writeScopes) {
        long now = System.currentTimeMillis();
        try (SqlSession session = factory.openSession(true)) {
            TeamMapper mapper = session.getMapper(TeamMapper.class);
            if (mapper.bumpNextTask(rootRunId, now) == 0) {
                throw new IllegalArgumentException("unknown team root: " + rootRunId);
            }
            Long no = mapper.currentTaskNo(rootRunId);
            if (no == null) {
                throw new IllegalStateException("could not allocate team task id");
            }
            TeamTask task = new TeamTask("task-" + no, 1, subject, description,
                    TeamTaskStatus.PENDING, null, null, blockedBy, writeScopes, now, now);
            mapper.insertTask(toRow(rootRunId, task));
            return task;
        }
    }

    @Override
    public synchronized Optional<TeamTask> find(String rootRunId, String taskId) {
        try (SqlSession session = factory.openSession(true)) {
            TeamTaskRow row = session.getMapper(TeamMapper.class).findTask(rootRunId, taskId);
            return row == null ? Optional.empty() : Optional.of(fromRow(row));
        }
    }

    @Override
    public synchronized List<TeamTask> list(String rootRunId) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(TeamMapper.class).listTasks(rootRunId).stream()
                    .map(MySqlTeamTaskStore::fromRow).toList();
        }
    }

    @Override
    public synchronized TeamTask save(String rootRunId, TeamTask expected, TeamTask next) {
        TeamTask saved = next.withRevision(expected.revision() + 1, System.currentTimeMillis());
        try (SqlSession session = factory.openSession(true)) {
            int updated = session.getMapper(TeamMapper.class)
                    .updateTask(rootRunId, toRow(rootRunId, saved), expected.revision());
            return updated == 0 ? null : saved;
        }
    }

    @Override
    public synchronized void close() {
        dataSource.forceCloseAll();
    }

    private static TeamTaskRow toRow(String rootRunId, TeamTask task) {
        TeamTaskRow row = new TeamTaskRow();
        row.rootRunId = rootRunId;
        row.taskId = task.id();
        row.revision = task.revision();
        row.subject = task.subject();
        row.description = task.description();
        row.status = task.status().id();
        row.ownerId = task.ownerId();
        row.ownerName = task.ownerName();
        row.blockedByJson = json(task.blockedBy());
        row.writeScopesJson = json(task.writeScopes());
        row.createdAt = task.createdAt();
        row.updatedAt = task.updatedAt();
        return row;
    }

    private static TeamTask fromRow(TeamTaskRow row) {
        return new TeamTask(row.taskId, row.revision, row.subject, row.description,
                TeamTaskStatus.fromId(row.status), row.ownerId, row.ownerName,
                strings(row.blockedByJson), strings(row.writeScopesJson), row.createdAt, row.updatedAt);
    }

    private static String json(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("could not encode team task", e);
        }
    }

    private static List<String> strings(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(value, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
