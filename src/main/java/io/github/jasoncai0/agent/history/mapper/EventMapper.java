package io.github.jasoncai0.agent.history.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface EventMapper {
    int upsert(@Param("runId") String runId,
               @Param("seq") long seq,
               @Param("ts") long ts,
               @Param("eventType") String eventType,
               @Param("data") String data,
               @Param("compactData") String compactData);

    List<EventRow> pageFull(@Param("runId") String runId,
                            @Param("before") long before,
                            @Param("limit") int limit);

    List<EventRow> pageCompact(@Param("runId") String runId,
                               @Param("before") long before,
                               @Param("limit") int limit);

    EventRow find(@Param("runId") String runId, @Param("seq") long seq);

    long lastSeq(@Param("runId") String runId);

    long eventCount(@Param("runId") String runId);

    Integer existsBefore(@Param("runId") String runId, @Param("seq") long seq);

    int deleteByRunIds(@Param("runIds") List<String> runIds);
}
