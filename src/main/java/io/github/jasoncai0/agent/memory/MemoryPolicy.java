package io.github.jasoncai0.agent.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic local selection, lifecycle/TTL filtering, ranking, and redaction rules around Mem0. */
public final class MemoryPolicy {
    public static final int MAX_RECALL = 5;
    public static final int MAX_GLOBAL_RECALL = 2;
    public static final double MIN_SCORE = 0.30;
    private static final int MAX_ITEM_CHARS = 800;
    private static final int MAX_CANDIDATE_CHARS = 1_200;
    private static final long DAY_MS = 86_400_000L;

    private MemoryPolicy() {
    }

    public static MemoryScope scope(MemorySettings settings, Path workspace, String sessionId) {
        return scope(settings, workspace, null, sessionId);
    }

    public static MemoryScope scope(MemorySettings settings, Path workspace,
                                    Path workspacesRoot, String sessionId) {
        Path root = workspace.toAbsolutePath().normalize();
        Path logicalRoot = root;
        if (workspacesRoot != null) {
            Path normalizedWorkspaces = workspacesRoot.toAbsolutePath().normalize();
            if (root.startsWith(normalizedWorkspaces)) {
                logicalRoot = normalizedWorkspaces;
            }
        }
        String name = logicalRoot.getFileName() == null ? "workspace" : logicalRoot.getFileName().toString();
        String explicitProject = settings == null ? "" : settings.projectId();
        String resolvedProjectId = explicitProject.isBlank() ? projectId(logicalRoot) : explicitProject;
        return new MemoryScope(settings.userId(), "jade", resolvedProjectId, name, sessionId);
    }

    public static String projectId(Path root) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return "project-" + hex;
        } catch (NoSuchAlgorithmException e) {
            return "project-" + Integer.toHexString(root.toString().hashCode());
        }
    }

    /** Returns TTL in days by memory kind (0 = no automatic expiry). */
    public static int ttlDaysForKind(String kind) {
        if (kind == null) return 90;
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "preference" -> 0;
            case "pitfall" -> 180;
            case "command_recipe" -> 30;
            case "decision", "project_fact", "fact" -> 90;
            default -> 90;
        };
    }

    public static Optional<MemoryCandidate> candidateFromUserMessage(String message, MemoryScope scope) {
        String text = message == null ? "" : message.strip();
        if (text.length() < 8 || isSensitive(text)) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        boolean preference = containsAny(lower,
                "以后", "今后", "记住", "默认", "偏好", "习惯", "总是", "always", "prefer", "default");
        boolean projectFact = containsAny(lower,
                "这个项目", "本仓库", "我们的约定", "约定", "决策", "using", "we use", "project uses");
        if (!preference && !projectFact) {
            return Optional.empty();
        }
        boolean global = preference && containsAny(lower, "全局", "所有项目", "跨项目", "all projects");
        if (text.length() > MAX_CANDIDATE_CHARS) {
            text = text.substring(0, MAX_CANDIDATE_CHARS);
        }
        return Optional.of(new MemoryCandidate(
                text,
                preference ? "preference" : "project_fact",
                "user",
                preference ? 0.8 : 0.65,
                true,
                global,
                scope.sessionId()
        ));
    }

    public static List<MemoryItem> select(List<MemoryItem> candidates, MemoryScope scope) {
        return select(candidates, scope, System.currentTimeMillis());
    }

    public static List<MemoryItem> select(List<MemoryItem> candidates, MemoryScope scope, long nowMillis) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> supersededIds = new HashSet<>();
        for (MemoryItem item : candidates) {
            if (item == null || !isActive(item, nowMillis)) continue;
            Object sup = item.metadata().get("supersedes");
            if (sup != null && !String.valueOf(sup).isBlank()) {
                supersededIds.add(String.valueOf(sup).trim());
            }
        }
        List<MemoryItem> filtered = new ArrayList<>();
        for (MemoryItem item : candidates) {
            if (item == null || item.text().isBlank() || item.score() < MIN_SCORE) continue;
            if (!isActive(item, nowMillis)) continue;
            if (!item.id().isEmpty() && supersededIds.contains(item.id())) continue;
            boolean local = scope.projectId().equals(item.projectId());
            if (!local && !item.global()) continue;
            filtered.add(item);
        }
        filtered.sort(Comparator.comparingDouble((MemoryItem item) -> compositeScore(item, nowMillis)).reversed());
        List<MemoryItem> selected = new ArrayList<>();
        int globals = 0;
        for (MemoryItem item : filtered) {
            if (item.global()) {
                if (globals >= MAX_GLOBAL_RECALL) continue;
                globals++;
            }
            selected.add(item);
            if (selected.size() >= MAX_RECALL) break;
        }
        return selected;
    }

    /** Returns true when item is active (not expired, superseded, pending, or rejected). */
    public static boolean isActive(MemoryItem item, long nowMillis) {
        if (item == null) return false;
        Map<String, Object> meta = item.metadata();
        Object rawStatus = meta.get("status");
        if (rawStatus != null) {
            String status = String.valueOf(rawStatus).trim().toLowerCase(Locale.ROOT);
            if ("expired".equals(status) || "superseded".equals(status)
                    || "pending".equals(status) || "rejected".equals(status)) {
                return false;
            }
        }
        long expiresAt = longMeta(meta.get("expires_at_ms"), 0L);
        if (expiresAt <= 0 && meta.get("expires_at") != null) {
            expiresAt = parseTimestamp(String.valueOf(meta.get("expires_at")));
        }
        return expiresAt <= 0 || expiresAt > nowMillis;
    }

    /** Composite ranking: 0.7 * semantic_score + 0.2 * recency + 0.1 * confidence. */
    public static double compositeScore(MemoryItem item, long nowMillis) {
        double semantic = clamp(item.score(), 0.0, 1.0);
        double confidence = clamp(doubleMeta(item.metadata().get("confidence"), 0.7), 0.0, 1.0);
        long ts = longMeta(item.metadata().get("last_verified_at_ms"), 0L);
        if (ts <= 0) {
            ts = longMeta(item.metadata().get("created_at_ms"), 0L);
        }
        if (ts <= 0 && !item.createdAt().isBlank()) {
            ts = parseTimestamp(item.createdAt());
        }
        double recency = 0.5;
        if (ts > 0 && nowMillis >= ts) {
            double ageDays = (nowMillis - ts) / (double) DAY_MS;
            recency = Math.exp(-ageDays / 90.0);
        }
        return 0.7 * semantic + 0.2 * recency + 0.1 * confidence;
    }

    public static String redact(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?i)sk-[a-z0-9_-]{8,}", "sk-[REDACTED]")
                .replaceAll("(?i)(password|passwd|token|secret|api[_-]?key)\\s*[=:]\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("-----BEGIN [A-Z ]+PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+PRIVATE KEY-----", "[REDACTED KEY]");
    }

    public static boolean isSensitive(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(lower, "sk-", "password=", "password:", "api_key=", "api key:",
                "token=", "secret=", "-----begin", "private key");
    }

    public static Map<String, Object> metadata(MemoryScope scope, MemoryCandidate candidate) {
        return metadata(scope, candidate, System.currentTimeMillis());
    }

    public static Map<String, Object> metadata(MemoryScope scope, MemoryCandidate candidate, long nowMillis) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("project_id", candidate.global() ? MemoryScope.GLOBAL_PROJECT : scope.projectId());
        map.put("project_name", candidate.global() ? "global" : scope.projectName());
        map.put("scope", candidate.global() ? "global" : "project");
        map.put("kind", candidate.kind());
        map.put("source", candidate.source());
        map.put("confidence", candidate.confidence());
        map.put("session_id", candidate.sessionId());
        map.put("source_run_id", candidate.sessionId());
        map.put("status", "active");
        map.put("created_at_ms", nowMillis);
        map.put("last_verified_at_ms", nowMillis);
        int ttlDays = ttlDaysForKind(candidate.kind());
        if (ttlDays > 0) {
            long expiresAtMs = nowMillis + ttlDays * DAY_MS;
            map.put("expires_at_ms", expiresAtMs);
            map.put("expires_at", Instant.ofEpochMilli(expiresAtMs).toString());
        }
        if (!candidate.supersedes().isBlank()) {
            map.put("supersedes", candidate.supersedes());
        }
        return map;
    }

    public static String truncate(String text) {
        String clean = redact(text == null ? "" : text.strip());
        return clean.length() <= MAX_ITEM_CHARS ? clean : clean.substring(0, MAX_ITEM_CHARS) + "…";
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static double doubleMeta(Object raw, double fallback) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw != null) {
            try {
                return Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static long longMeta(Object raw, long fallback) {
        if (raw instanceof Number n) return n.longValue();
        if (raw != null) {
            try {
                return Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static long parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Instant.parse(raw.trim()).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
