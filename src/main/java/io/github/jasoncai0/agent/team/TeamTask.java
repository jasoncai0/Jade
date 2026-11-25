package io.github.jasoncai0.agent.team;

import java.util.List;
import java.util.Objects;

public record TeamTask(
        String id,
        long revision,
        String subject,
        String description,
        TeamTaskStatus status,
        String ownerId,
        String ownerName,
        List<String> blockedBy,
        List<String> writeScopes,
        long createdAt,
        long updatedAt
) {
    public TeamTask {
        if (id == null || !id.matches("task-\\d+")) throw new IllegalArgumentException("invalid task id");
        if (revision < 1) throw new IllegalArgumentException("revision must be >= 1");
        subject = Objects.requireNonNull(subject, "subject").trim();
        if (subject.isEmpty() || subject.length() > 200) throw new IllegalArgumentException("subject must be 1..200 chars");
        description = description == null ? "" : description;
        if (description.length() > 16_384) throw new IllegalArgumentException("description is too long");
        status = Objects.requireNonNull(status, "status");
        ownerId = blankToNull(ownerId);
        ownerName = blankToNull(ownerName);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        writeScopes = writeScopes == null ? List.of() : List.copyOf(writeScopes);
    }

    public TeamTask withStatus(TeamTaskStatus next, long now) {
        return new TeamTask(id, revision, subject, description, next, ownerId, ownerName,
                blockedBy, writeScopes, createdAt, now);
    }

    public TeamTask ownedBy(String id, String name, TeamTaskStatus next, long now) {
        return new TeamTask(this.id, revision, subject, description, next, blankToNull(id), blankToNull(name),
                blockedBy, writeScopes, createdAt, now);
    }

    public TeamTask withRevision(long next, long now) {
        return new TeamTask(id, next, subject, description, status, ownerId, ownerName,
                blockedBy, writeScopes, createdAt, now);
    }

    public TeamTask withFields(String subject, String description, List<String> blockedBy,
                               List<String> writeScopes, long now) {
        return new TeamTask(id, revision,
                subject == null ? this.subject : subject,
                description == null ? this.description : description,
                status, ownerId, ownerName,
                blockedBy == null ? this.blockedBy : blockedBy,
                writeScopes == null ? this.writeScopes : writeScopes,
                createdAt, now);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
