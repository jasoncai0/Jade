package io.github.jasoncai0.agent.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Deterministic local selection and redaction rules around Mem0. */
public final class MemoryPolicy {
    public static final int MAX_RECALL = 5;
    public static final int MAX_GLOBAL_RECALL = 2;
    public static final double MIN_SCORE = 0.30;
    private static final int MAX_ITEM_CHARS = 800;
    private static final int MAX_CANDIDATE_CHARS = 1_200;

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
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<MemoryItem> filtered = new ArrayList<>();
        for (MemoryItem item : candidates) {
            if (item == null || item.text().isBlank() || item.score() < MIN_SCORE) continue;
            boolean local = scope.projectId().equals(item.projectId());
            if (!local && !item.global()) continue;
            filtered.add(item);
        }
        filtered.sort(Comparator.comparingDouble(MemoryItem::score).reversed());
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

    public static String redact(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?i)sk-[a-z0-9_-]{8,}", "sk-[REDACTED]")
                .replaceAll("(?i)(password|passwd|token|secret|api[_-]?key)\\s*[=:]\\s*\\S+", "$1=[REDACTED]")
                .replaceAll("-----BEGIN [A-Z ]+PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+PRIVATE KEY-----", "[REDACTED KEY]");
    }

    public static boolean isSensitive(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return containsAny(lower, "sk-", "password=", "password:", "api_key=", "api key:",
                "token=", "secret=", "-----begin", "private key");
    }

    public static Map<String, Object> metadata(MemoryScope scope, MemoryCandidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("project_id", candidate.global() ? MemoryScope.GLOBAL_PROJECT : scope.projectId());
        map.put("project_name", candidate.global() ? "global" : scope.projectName());
        map.put("scope", candidate.global() ? "global" : "project");
        map.put("kind", candidate.kind());
        map.put("source", candidate.source());
        map.put("confidence", candidate.confidence());
        map.put("session_id", candidate.sessionId());
        return map;
    }

    public static String truncate(String text) {
        String clean = redact(text == null ? "" : text.strip());
        return clean.length() <= MAX_ITEM_CHARS ? clean : clean.substring(0, MAX_ITEM_CHARS) + "…";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
