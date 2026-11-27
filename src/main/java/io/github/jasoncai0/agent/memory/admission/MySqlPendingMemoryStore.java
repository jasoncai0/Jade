package io.github.jasoncai0.agent.memory.admission;

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

/** MySQL-backed PendingMemoryStore using parameterized MyBatis statements and short transactions. */
public final class MySqlPendingMemoryStore implements PendingMemoryStore {
    private static final int MAX_LIMIT = 200;

    private final PooledDataSource dataSource;
    private final SqlSessionFactory factory;

    public MySqlPendingMemoryStore(MysqlSettings settings) {
        if (settings == null || settings.url() == null || settings.url().isBlank()) {
            throw new IllegalArgumentException("MySQL settings are required");
        }
        this.dataSource = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                settings.url(),
                settings.user(),
                settings.password()
        );
        this.dataSource.setPoolMaximumActiveConnections(4);
        this.dataSource.setPoolMaximumIdleConnections(2);
        this.dataSource.setPoolPingEnabled(true);
        this.dataSource.setPoolPingQuery("SELECT 1");
        this.dataSource.setPoolPingConnectionsNotUsedFor(30_000);

        Configuration configuration = new Configuration(
                new Environment("memory_pending", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PendingMemoryMapper.class);
        this.factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(PendingMemoryMapper.class).createTable();
        } catch (RuntimeException e) {
            dataSource.forceCloseAll();
            throw e;
        }
    }

    @Override
    public PendingMemoryItem enqueue(PendingMemoryItem item) {
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(PendingMemoryMapper.class).insert(toRow(item));
            return item;
        }
    }

    @Override
    public List<PendingMemoryItem> listPending(String projectId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(PendingMemoryMapper.class)
                    .listPending(projectId == null ? "" : projectId, safeLimit)
                    .stream()
                    .map(MySqlPendingMemoryStore::fromRow)
                    .toList();
        }
    }

    @Override
    public Optional<PendingMemoryItem> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try (SqlSession session = factory.openSession(true)) {
            PendingMemoryRow row = session.getMapper(PendingMemoryMapper.class).findById(id);
            return row == null ? Optional.empty() : Optional.of(fromRow(row));
        }
    }

    @Override
    public boolean markApproved(String id, String memoryId, long updatedAtMs) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(PendingMemoryMapper.class)
                    .markApproved(id, memoryId == null ? "" : memoryId, updatedAtMs) > 0;
        }
    }

    @Override
    public boolean markRejected(String id, long updatedAtMs) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(PendingMemoryMapper.class).markRejected(id, updatedAtMs) > 0;
        }
    }

    @Override
    public void close() {
        dataSource.forceCloseAll();
    }

    private static PendingMemoryRow toRow(PendingMemoryItem item) {
        PendingMemoryRow row = new PendingMemoryRow();
        row.id = cut(item.id(), 48);
        row.projectId = cut(item.projectId(), 96);
        row.projectName = cut(item.projectName(), 128);
        row.scope = cut(item.scope(), 16);
        row.kind = cut(item.kind(), 32);
        row.statementText = cut(item.statement(), 1024);
        row.confidence = item.confidence();
        row.reason = cut(item.reason(), 512);
        row.evidenceSource = cut(item.evidenceSource(), 32);
        row.evidenceMsgIndex = item.evidenceMessageIndex();
        row.evidenceQuote = cut(item.evidenceQuote(), 1024);
        row.supersedesId = cut(item.supersedes(), 96);
        row.sourceRunId = cut(item.sourceRunId(), 64);
        row.status = cut(item.status(), 16);
        row.memoryId = cut(item.memoryId(), 96);
        row.createdAtMs = item.createdAtMs();
        row.updatedAtMs = item.updatedAtMs();
        return row;
    }

    private static PendingMemoryItem fromRow(PendingMemoryRow row) {
        return new PendingMemoryItem(
                row.id,
                row.projectId,
                row.projectName,
                row.scope,
                row.kind,
                row.statementText,
                row.confidence,
                row.reason,
                row.evidenceSource,
                row.evidenceMsgIndex,
                row.evidenceQuote,
                row.supersedesId,
                row.sourceRunId,
                row.status,
                row.memoryId,
                row.createdAtMs,
                row.updatedAtMs
        );
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
