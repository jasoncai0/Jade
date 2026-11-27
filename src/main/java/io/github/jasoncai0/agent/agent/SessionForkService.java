package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.history.EventStore;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.team.TeamService;
import io.github.jasoncai0.agent.workspace.Workspace;
import io.github.jasoncai0.agent.workspace.WorkspaceFork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles session workspace/transcript forking and bounded event history reads
 * so {@link AgentService} stays focused on run lifecycle and orchestration.
 */
final class SessionForkService {
    private SessionForkService() {
    }

    static AgentService.ForkResult fork(AgentRun parent, String name, Path workspacesDir,
                                        Object startLock, EventStore eventStore,
                                        Map<String, AgentRun> runs, TeamService team) {
        RunSnapshot parentSnapshot = parent.snapshot();
        if (parent.running() || !parentSnapshot.terminal()) {
            throw new IllegalStateException("Only a finished session can be forked");
        }
        Path source = parent.workspace().root();
        String parentId = parent.id();
        String parentWorkspace = parentSnapshot.workspace();
        String newId = "fork-" + UUID.randomUUID().toString().substring(0, 8);
        Workspace target;
        String directoryName;
        synchronized (startLock) {
            String base = name == null || name.isBlank()
                    ? AgentService.validateName(parentWorkspace)
                    : AgentService.validateName(name);
            directoryName = base + "-fork-" + UUID.randomUUID().toString().substring(0, 6);
            Path directory = workspacesDir.resolve(directoryName).normalize();
            if (!directory.startsWith(workspacesDir)) {
                throw new IllegalArgumentException("Invalid workspace name");
            }
            if (Files.exists(directory)) {
                throw new IllegalArgumentException("Workspace already exists: " + directoryName);
            }
            target = Workspace.open(directory);
            WorkspaceFork.copy(source, target.root());
        }

        List<ChatMessage> messages = RunArtifacts.loadMessages(source);
        List<AgentEvent> parentEvents = SessionRecoveryService.loadForkEvents(eventStore, parent);
        long forkSeq = parentEvents.stream().mapToLong(AgentEvent::seq).max().orElse(0L);
        long now = System.currentTimeMillis();
        List<String> files = target.listFiles(500, 8).stream()
                .map(Workspace.FileEntry::path)
                .toList();
        RunSnapshot forkSnapshot = new RunSnapshot(
                newId,
                parentSnapshot.task(),
                directoryName,
                parentSnapshot.status(),
                parentSnapshot.phase(),
                parentSnapshot.phaseLabel(),
                parentSnapshot.step(),
                parentSnapshot.maxSteps(),
                parentSnapshot.plan(),
                files,
                parentSnapshot.usage(),
                parentSnapshot.finalAnswer(),
                parentSnapshot.error(),
                parentSnapshot.verification(),
                now,
                now,
                ".agent/transcript.json",
                parentId,
                forkSeq
        );
        RunState state = RunState.restore(forkSnapshot);
        List<AgentEvent> copiedEvents = new ArrayList<>(parentEvents.size() + 1);
        for (AgentEvent event : parentEvents) {
            copiedEvents.add(new AgentEvent(event.seq(), event.timestamp(), event.type(),
                    SessionRecoveryService.rebase(event.data(), parentId, newId, parentWorkspace, directoryName)));
        }
        copiedEvents.add(new AgentEvent(forkSeq + 1, now, "fork.created", Map.of(
                "parentRunId", parentId,
                "parentSeq", forkSeq,
                "workspace", directoryName,
                "task", parentSnapshot.task()
        )));
        RunEventLog events = RunEventLog.restoredWithEvents(newId, eventStore, copiedEvents);

        AgentRun run = new AgentRun(newId, target, state, events);
        runs.put(newId, run);
        team.ensureRoot(newId);
        RunArtifacts.persist(target, state.snapshot(), messages, events);
        if (eventStore != null) {
            eventStore.importRun(state.snapshot(), copiedEvents);
        }
        Log.info("agent.run.forked", Map.of(
                "runId", newId,
                "parentRunId", parentId,
                "workspace", directoryName,
                "events", parentEvents.size()
        ));
        return new AgentService.ForkResult(newId, parentId, directoryName);
    }

    static EventStore.Page history(AgentRun run, EventStore eventStore,
                                   long before, int limit, boolean compact) {
        if (eventStore == null) {
            List<AgentEvent> events = run.events().snapshot();
            long max = events.stream().mapToLong(AgentEvent::seq).max().orElse(0);
            List<AgentEvent> filtered = events.stream()
                    .filter(e -> before <= 0 || e.seq() < before)
                    .toList();
            int safeLimit = Math.max(1, limit);
            int from = Math.max(0, filtered.size() - safeLimit);
            List<AgentEvent> tail = filtered.subList(from, filtered.size());
            long next = tail.isEmpty() ? before : tail.get(0).seq();
            return new EventStore.Page(tail, next, from > 0, max, events.size());
        }
        return eventStore.page(run.id(), before, limit, compact);
    }

    static Optional<AgentEvent> fullEvent(AgentRun run, EventStore eventStore, long seq) {
        if (eventStore == null) {
            return run.events().snapshot().stream().filter(e -> e.seq() == seq).findFirst();
        }
        return eventStore.event(run.id(), seq);
    }
}
