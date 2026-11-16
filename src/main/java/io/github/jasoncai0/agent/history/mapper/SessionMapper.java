package io.github.jasoncai0.agent.history.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SessionMapper {
    int upsert(@Param("runId") String runId,
               @Param("snapshot") String snapshot,
               @Param("updatedAt") long updatedAt);

    List<String> listSnapshots(@Param("limit") int limit);

    String findSnapshot(@Param("runId") String runId);

    long countSessions();
}
