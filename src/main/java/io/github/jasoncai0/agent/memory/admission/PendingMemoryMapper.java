package io.github.jasoncai0.agent.memory.admission;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Parameterized MyBatis SQL mapper for {@code jade_memory_pending} (zero SELECT *, zero JSON columns). */
public interface PendingMemoryMapper {
    String COLUMNS = "id, project_id, project_name, scope, kind, statement_text, confidence, reason, "
            + "evidence_source, evidence_msg_index, evidence_quote, supersedes_id, source_run_id, "
            + "status, memory_id, created_at_ms, updated_at_ms";

    @Update("""
            CREATE TABLE IF NOT EXISTS jade_memory_pending (
                id VARCHAR(48) NOT NULL PRIMARY KEY,
                project_id VARCHAR(96) NOT NULL,
                project_name VARCHAR(128) NOT NULL,
                scope VARCHAR(16) NOT NULL,
                kind VARCHAR(32) NOT NULL,
                statement_text VARCHAR(1024) NOT NULL,
                confidence DOUBLE NOT NULL,
                reason VARCHAR(512) NOT NULL,
                evidence_source VARCHAR(32) NOT NULL,
                evidence_msg_index INT NOT NULL,
                evidence_quote VARCHAR(1024) NOT NULL,
                supersedes_id VARCHAR(96) NOT NULL DEFAULT '',
                source_run_id VARCHAR(64) NOT NULL,
                status VARCHAR(16) NOT NULL,
                memory_id VARCHAR(96) NOT NULL DEFAULT '',
                created_at_ms BIGINT NOT NULL,
                updated_at_ms BIGINT NOT NULL,
                INDEX idx_pending_proj_status_created (project_id, status, created_at_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """)
    void createTable();

    @Insert("""
            INSERT INTO jade_memory_pending (
                id, project_id, project_name, scope, kind, statement_text, confidence, reason,
                evidence_source, evidence_msg_index, evidence_quote, supersedes_id, source_run_id,
                status, memory_id, created_at_ms, updated_at_ms
            ) VALUES (
                #{id}, #{projectId}, #{projectName}, #{scope}, #{kind}, #{statementText}, #{confidence}, #{reason},
                #{evidenceSource}, #{evidenceMsgIndex}, #{evidenceQuote}, #{supersedesId}, #{sourceRunId},
                #{status}, #{memoryId}, #{createdAtMs}, #{updatedAtMs}
            )
            ON DUPLICATE KEY UPDATE updated_at_ms = VALUES(updated_at_ms)
            """)
    int insert(PendingMemoryRow row);

    @Select("SELECT " + COLUMNS + " FROM jade_memory_pending "
            + "WHERE status = 'pending' AND (project_id = #{projectId} OR project_id = 'global' OR scope = 'global') "
            + "ORDER BY created_at_ms DESC LIMIT #{limit}")
    List<PendingMemoryRow> listPending(@Param("projectId") String projectId, @Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM jade_memory_pending WHERE id = #{id} LIMIT 1")
    PendingMemoryRow findById(@Param("id") String id);

    @Update("""
            UPDATE jade_memory_pending
            SET status = 'approved', memory_id = #{memoryId}, updated_at_ms = #{updatedAtMs}
            WHERE id = #{id} AND status = 'pending'
            """)
    int markApproved(@Param("id") String id,
                     @Param("memoryId") String memoryId,
                     @Param("updatedAtMs") long updatedAtMs);

    @Update("""
            UPDATE jade_memory_pending
            SET status = 'rejected', updated_at_ms = #{updatedAtMs}
            WHERE id = #{id} AND status = 'pending'
            """)
    int markRejected(@Param("id") String id, @Param("updatedAtMs") long updatedAtMs);
}
