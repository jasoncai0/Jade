package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.history.EventStore;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Rehydrates persisted sessions from MySQL or local `.agent/` files and handles
 * event rebasing for forked sessions.
 */
final class SessionRecoveryService {
    private static final ObjectMapper REBASE_MAPPER = new ObjectMapper();
    private static final int MAX_RESTORED_SESSIONS = 200;

    private SessionRecoveryService() {
    }

    static void restoreAll(AppConfig config, EventStore eventStore, Map<String, AgentRun> runs) {
        if (eventStore != null) {
            if (eventStore.isEmpty()) {
                migrateLegacySessions(config, eventStore);
            }
            restoreFromStore(config, eventStore, runs);
            return;
        }
        restoreFromLocalWorkspaces(config, runs);
    }

    static List<AgentEvent> loadForkEvents(EventStore eventStore, AgentRun parent) {
        List<AgentEvent> events = new ArrayList<>();
        if (eventStore != null) {
            long before = 0;
            while (events.size() < 20_000) {
                EventStore.Page page = eventStore.page(parent.id(), before, 500, true);
                events.addAll(page.events());
                if (!page.hasMore()) {
                    break;
                }
                before = page.nextBefore();
            }
        } else {
            events.addAll(RunArtifacts.load(parent.workspace().root())
                    .map(RunArtifacts.StoredRun::events)
                    .orElse(List.of()));
        }
        events.sort(Comparator.comparingLong(AgentEvent::seq));
        if (events.size() > 20_000) {
            events = new ArrayList<>(events.subList(events.size() - 20_000, events.size()));
        }
        return events;
    }

    static Map<String, Object> rebase(Map<String, Object> data, String parentId,
                                      String newId, String parentWorkspace,
                                      String newWorkspace) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rebaseValue(
                data, parentId, newId, parentWorkspace, newWorkspace);
        return result == null ? Map.of() : result;
    }

    private static Object rebaseValue(Object value, String parentId, String newId,
                                      String parentWorkspace, String newWorkspace) {
        if (value != null && value.getClass().isRecord()) {
            Object converted = REBASE_MAPPER.convertValue(value, Object.class);
            return rebaseValue(converted, parentId, newId, parentWorkspace, newWorkspace);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object nested = entry.getValue();
                if (("id".equals(key) || "runId".equals(key)) && parentId.equals(nested)) {
                    out.put(key, newId);
                } else if ("workspace".equals(key) && parentWorkspace.equals(nested)) {
                    out.put(key, newWorkspace);
                } else {
                    out.put(key, rebaseValue(nested, parentId, newId, parentWorkspace, newWorkspace));
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(rebaseValue(item, parentId, newId, parentWorkspace, newWorkspace));
            }
            return out;
        }
        return value;
    }

    private static void restoreFromLocalWorkspaces(AppConfig config, Map<String, AgentRun> runs) {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> entries = Files.list(config.workspacesDir())) {
            entries.filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> Files.isRegularFile(dir.resolve(".agent/transcript.json")))
                    .forEach(candidates::add);
        } catch (IOException e) {
            Log.warn("agent.sessions.scan_failed", Map.of("error", String.valueOf(e.getMessage())));
            return;
        }
        candidates.sort(Comparator.comparingLong(SessionRecoveryService::sessionModified).reversed());

        int restored = 0;
        for (Path directory : candidates) {
            if (restored >= MAX_RESTORED_SESSIONS) {
                break;
            }
            try {
                Workspace workspace = Workspace.open(directory);
                RunArtifacts.StoredRun stored = RunArtifacts.load(workspace.root()).orElse(null);
                if (stored == null) {
                    continue;
                }
                RunSnapshot snapshot = stored.snapshot();
                if (runs.containsKey(snapshot.id())) {
                    Log.warn("agent.sessions.duplicate", Map.of("runId", snapshot.id(),
                            "workspace", workspace.root().getFileName().toString()));
                    continue;
                }
                RunState state = RunState.restore(snapshot);
                RunEventLog events = RunEventLog.restore(stored.events());
                AgentRun run = new AgentRun(snapshot.id(), workspace, state, events);
                runs.put(snapshot.id(), run);
                restored++;
            } catch (RuntimeException e) {
                Log.warn("agent.sessions.restore_failed", Map.of(
                        "workspace", directory.getFileName().toString(),
                        "error", String.valueOf(e.getMessage())));
            }
        }
        Log.info("agent.sessions.restored", Map.of(
                "restored", restored,
                "candidates", candidates.size()));
    }

    private static void restoreFromStore(AppConfig config, EventStore eventStore, Map<String, AgentRun> runs) {
        int restored = 0;
        for (RunSnapshot stored : eventStore.snapshots(MAX_RESTORED_SESSIONS)) {
            try {
                if (runs.containsKey(stored.id())) {
                    continue;
                }
                Path directory = config.workspacesDir().resolve(stored.workspace()).normalize();
                if (!directory.startsWith(config.workspacesDir()) || !Files.isDirectory(directory)) {
                    continue;
                }
                Workspace workspace = Workspace.open(directory);
                RunSnapshot persisted = stored;
                if (!stored.terminal()) {
                    RunSnapshot recovered = RunArtifacts.load(workspace.root())
                            .map(RunArtifacts.StoredRun::snapshot)
                            .filter(RunSnapshot::terminal)
                            .orElse(null);
                    if (recovered != null) {
                        stored = recovered;
                    }
                }
                RunState state = RunState.restore(stored);
                RunSnapshot normalized = state.snapshot();
                if (!normalized.status().equals(persisted.status())
                        || !Objects.equals(normalized.error(), persisted.error())) {
                    eventStore.saveSnapshot(normalized);
                }
                RunEventLog events = RunEventLog.restored(stored.id(), eventStore,
                        eventStore.lastSeq(stored.id()));
                runs.put(stored.id(), new AgentRun(stored.id(), workspace, state, events));
                restored++;
            } catch (RuntimeException e) {
                Log.warn("agent.sessions.mysql_restore_failed", Map.of(
                        "runId", stored.id(),
                        "error", String.valueOf(e.getMessage())));
            }
        }
        Log.info("agent.sessions.restored", Map.of("restored", restored, "store", "mysql"));
    }

    private static void migrateLegacySessions(AppConfig config, EventStore eventStore) {
        int imported = 0;
        try (Stream<Path> entries = Files.list(config.workspacesDir())) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                if (directory.getFileName().toString().startsWith(".")) {
                    continue;
                }
                if (!Files.isRegularFile(directory.resolve(".agent/transcript.json"))) {
                    continue;
                }
                try {
                    Workspace workspace = Workspace.open(directory);
                    RunArtifacts.StoredRun stored = RunArtifacts.load(workspace.root()).orElse(null);
                    if (stored == null) {
                        continue;
                    }
                    eventStore.importRun(stored.snapshot(), stored.events());
                    imported++;
                } catch (RuntimeException e) {
                    Log.warn("agent.migration.skip", Map.of(
                            "workspace", directory.getFileName().toString(),
                            "error", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException e) {
            Log.warn("agent.migration.scan_failed", Map.of("error", String.valueOf(e.getMessage())));
        }
        Log.info("agent.sessions.migrated", Map.of("imported", imported));
    }

    private static long sessionModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory.resolve(".agent/transcript.json")).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
