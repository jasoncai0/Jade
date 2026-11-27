package io.github.jasoncai0.agent.memory.admission;

/** MyBatis projection row for {@code jade_memory_pending}. */
public final class PendingMemoryRow {
    public String id;
    public String projectId;
    public String projectName;
    public String scope;
    public String kind;
    public String statementText;
    public double confidence;
    public String reason;
    public String evidenceSource;
    public int evidenceMsgIndex;
    public String evidenceQuote;
    public String supersedesId;
    public String sourceRunId;
    public String status;
    public String memoryId;
    public long createdAtMs;
    public long updatedAtMs;
}
