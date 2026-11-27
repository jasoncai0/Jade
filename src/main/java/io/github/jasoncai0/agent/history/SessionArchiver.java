package io.github.jasoncai0.agent.history;

import io.github.jasoncai0.agent.agent.RunSnapshot;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Archives `.agent/` artifacts of expired or excess terminal sessions before
 * removing their workspace directory and MySQL event rows.
 */
public final class SessionArchiver {
    public static final int DEFAULT_KEEP_COUNT = 200;
    public static final int DEFAULT_MAX_AGE_DAYS = 90;
    private static final DateTimeFormatter ARCHIVE_BUCKET =
            DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private SessionArchiver() {
    }

    public record Policy(int keepCount, int maxAgeDays, boolean dryRun) {
        public Policy {
            if (keepCount < 0 || keepCount > 100_000) {
                throw new IllegalArgumentException("keepCount must be between 0 and 100000");
            }
            if (maxAgeDays < 0 || maxAgeDays > 3_650) {
                throw new IllegalArgumentException("maxAgeDays must be between 0 and 3650");
            }
        }
    }

    public record PruneResult(
            boolean dryRun,
            int scanned,
            int kept,
            List<String> prunedRunIds,
            String archiveRoot,
            int deletedDbRows
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("dryRun", dryRun);
            map.put("scanned", scanned);
            map.put("kept", kept);
            map.put("prunedCount", prunedRunIds.size());
            map.put("prunedRunIds", prunedRunIds);
            map.put("archiveRoot", archiveRoot);
            map.put("deletedDbRows", deletedDbRows);
            return map;
        }
    }

    public static PruneResult prune(Path workspacesDir, List<RunSnapshot> allRuns,
                                    Set<String> activeRunIds, EventStore eventStore,
                                    Policy policy, long nowMillis) {
        Path root = workspacesDir.toAbsolutePath().normalize();
        Path archiveBase = root.resolve(".archives");
        List<RunSnapshot> sorted = new ArrayList<>(allRuns);
        sorted.sort(Comparator.comparingLong(SessionArchiver::activityTime).reversed());

        long cutoff = policy.maxAgeDays() > 0
                ? nowMillis - policy.maxAgeDays() * 86_400_000L
                : Long.MIN_VALUE;

        List<RunSnapshot> candidates = new ArrayList<>();
        int terminalSeen = 0;
        int kept = 0;
        for (RunSnapshot snapshot : sorted) {
            if (snapshot == null || activeRunIds.contains(snapshot.id()) || !snapshot.terminal()) {
                kept++;
                continue;
            }
            // Subagent/team runs share their root's workspace; prune them with root.
            if (snapshot.id().startsWith("sub-") || snapshot.id().startsWith("team-")) {
                continue;
            }
            terminalSeen++;
            boolean excessCount = terminalSeen > policy.keepCount();
            boolean expiredAge = policy.maxAgeDays() > 0 && activityTime(snapshot) < cutoff;
            if (excessCount || expiredAge) {
                candidates.add(snapshot);
            } else {
                kept++;
            }
        }

        List<String> prunedIds = candidates.stream().map(RunSnapshot::id).toList();
        if (policy.dryRun() || candidates.isEmpty()) {
            return new PruneResult(policy.dryRun(), sorted.size(), kept, prunedIds,
                    archiveBase.toString(), 0);
        }

        for (RunSnapshot candidate : candidates) {
            if (candidate.workspace() == null || candidate.workspace().isBlank()) {
                continue;
            }
            Path workspace = root.resolve(candidate.workspace()).normalize();
            if (!workspace.startsWith(root) || workspace.equals(root) || !Files.isDirectory(workspace)) {
                continue;
            }
            archiveAgentDir(workspace, archiveBase, candidate);
            deleteTreeQuietly(workspace);
        }

        // Also include any child sub-/team- snapshots whose parent workspace matched.
        Set<String> prunedWorkspaces = Set.copyOf(candidates.stream()
                .map(RunSnapshot::workspace)
                .filter(w -> w != null && !w.isBlank())
                .toList());
        List<String> dbIds = new ArrayList<>(prunedIds);
        for (RunSnapshot snapshot : sorted) {
            if ((snapshot.id().startsWith("sub-") || snapshot.id().startsWith("team-"))
                    && snapshot.workspace() != null
                    && prunedWorkspaces.contains(snapshot.workspace())) {
                dbIds.add(snapshot.id());
            }
        }
        int deletedDb = eventStore == null ? 0 : eventStore.deleteRuns(dbIds);
        return new PruneResult(false, sorted.size(), kept, prunedIds, archiveBase.toString(), deletedDb);
    }

    private static long activityTime(RunSnapshot snapshot) {
        return snapshot.finishedAt() > 0 ? snapshot.finishedAt() : snapshot.startedAt();
    }

    private static void archiveAgentDir(Path workspace, Path archiveBase, RunSnapshot snapshot) {
        Path agentDir = workspace.resolve(".agent");
        if (!Files.isDirectory(agentDir)) {
            return;
        }
        String bucket = ARCHIVE_BUCKET.format(Instant.ofEpochMilli(
                Math.max(1L, activityTime(snapshot))));
        String folder = snapshot.workspace() + "-" + snapshot.id();
        Path target = archiveBase.resolve(bucket).resolve(folder).resolve(".agent").normalize();
        if (!target.startsWith(archiveBase)) {
            return;
        }
        try {
            Files.walkFileTree(agentDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.getFileName() != null && "tool-results".equals(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(agentDir.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path dest = target.resolve(agentDir.relativize(file).toString());
                    Files.createDirectories(dest.getParent());
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort archive; if disk is full, do not crash the caller.
        }
    }

    private static void deleteTreeQuietly(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
