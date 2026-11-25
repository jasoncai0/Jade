package io.github.jasoncai0.agent.team;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TeamMapper {
    @Update("CREATE TABLE IF NOT EXISTS jade_teams ("
            + "root_run_id VARCHAR(80) PRIMARY KEY,"
            + "enabled TINYINT(1) NOT NULL DEFAULT 0,"
            + "next_task_no BIGINT NOT NULL DEFAULT 1,"
            + "created_at BIGINT NOT NULL,"
            + "updated_at BIGINT NOT NULL"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTeams();

    @Update("CREATE TABLE IF NOT EXISTS jade_team_tasks ("
            + "root_run_id VARCHAR(80) NOT NULL,"
            + "task_id VARCHAR(40) NOT NULL,"
            + "revision BIGINT NOT NULL,"
            + "subject VARCHAR(200) NOT NULL,"
            + "description MEDIUMTEXT NOT NULL,"
            + "status VARCHAR(24) NOT NULL,"
            + "owner_id VARCHAR(80) NULL,"
            + "owner_name VARCHAR(80) NULL,"
            + "blocked_by_json TEXT NOT NULL,"
            + "write_scopes_json TEXT NOT NULL,"
            + "created_at BIGINT NOT NULL,"
            + "updated_at BIGINT NOT NULL,"
            + "PRIMARY KEY(root_run_id, task_id),"
            + "KEY idx_team_status(root_run_id, status)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTasks();

    @Insert("INSERT IGNORE INTO jade_teams(root_run_id, enabled, next_task_no, created_at, updated_at) "
            + "VALUES(#{rootRunId}, #{enabled}, 1, #{now}, #{now})")
    int ensureTeam(@Param("rootRunId") String rootRunId, @Param("enabled") boolean enabled, @Param("now") long now);

    @Select("SELECT enabled FROM jade_teams WHERE root_run_id = #{rootRunId}")
    Boolean enabled(@Param("rootRunId") String rootRunId);

    @Update("UPDATE jade_teams SET enabled = #{enabled}, updated_at = #{now} WHERE root_run_id = #{rootRunId}")
    int setEnabled(@Param("rootRunId") String rootRunId, @Param("enabled") boolean enabled, @Param("now") long now);

    @Update("UPDATE jade_teams SET next_task_no = next_task_no + 1, updated_at = #{now} WHERE root_run_id = #{rootRunId}")
    int bumpNextTask(@Param("rootRunId") String rootRunId, @Param("now") long now);

    @Select("SELECT next_task_no - 1 FROM jade_teams WHERE root_run_id = #{rootRunId}")
    Long currentTaskNo(@Param("rootRunId") String rootRunId);

    @Insert("INSERT INTO jade_team_tasks(root_run_id, task_id, revision, subject, description, status, "
            + "owner_id, owner_name, blocked_by_json, write_scopes_json, created_at, updated_at) "
            + "VALUES(#{rootRunId}, #{taskId}, #{revision}, #{subject}, #{description}, #{status}, "
            + "#{ownerId}, #{ownerName}, #{blockedByJson}, #{writeScopesJson}, #{createdAt}, #{updatedAt})")
    int insertTask(TeamTaskRow row);

    @Select("SELECT * FROM jade_team_tasks WHERE root_run_id = #{rootRunId} AND task_id = #{taskId}")
    TeamTaskRow findTask(@Param("rootRunId") String rootRunId, @Param("taskId") String taskId);

    @Select("SELECT * FROM jade_team_tasks WHERE root_run_id = #{rootRunId} ORDER BY CAST(SUBSTRING(task_id, 6) AS UNSIGNED)")
    List<TeamTaskRow> listTasks(@Param("rootRunId") String rootRunId);

    @Update("UPDATE jade_team_tasks SET revision = #{next.revision}, subject = #{next.subject}, "
            + "description = #{next.description}, status = #{next.status}, owner_id = #{next.ownerId}, "
            + "owner_name = #{next.ownerName}, blocked_by_json = #{next.blockedByJson}, "
            + "write_scopes_json = #{next.writeScopesJson}, updated_at = #{next.updatedAt} "
            + "WHERE root_run_id = #{rootRunId} AND task_id = #{next.taskId} AND revision = #{expectedRevision}")
    int updateTask(@Param("rootRunId") String rootRunId, @Param("next") TeamTaskRow next,
                   @Param("expectedRevision") long expectedRevision);
}
