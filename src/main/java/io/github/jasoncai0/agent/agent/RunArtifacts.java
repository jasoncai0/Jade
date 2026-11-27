package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run leaves a readable transcript plus the raw event stream in .agent/.
 * The same files are enough to show the run again after a server restart.
 */
public final class RunArtifacts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TRANSCRIPT = ".agent/transcript.json";
    private static final int MAX_EVENTS = 100_000;

    private RunArtifacts() {
    }

    public record StoredRun(RunSnapshot snapshot, List<AgentEvent> events) {
    }

    /** Append a model-authored reflection without putting it in the transcript. */
    public static void appendReflection(Workspace workspace, String runId, int step, String arguments) {
        try {
            Path dir = workspace.root().resolve(".agent");
            Files.createDirectories(dir);
            ObjectNode line = JSON.createObjectNode();
            line.put("ts", System.currentTimeMillis());
            line.put("runId", runId);
            line.put("step", step);
            line.put("arguments", arguments == null ? "" : arguments);
            Files.writeString(dir.resolve("reflections.jsonl"), JSON.writeValueAsString(line) + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // Reflection is auxiliary; never fail the run because its audit line failed.
        }
    }

    public static String persist(Workspace workspace, RunSnapshot snapshot,
                                 List<ChatMessage> messages, RunEventLog events) {
        return persist(workspace, snapshot, messages, events, ".agent");
    }

    /**
     * Persists under an explicit artifact directory. Subagent runs use
     * {@code .agent/subagents/<id>} so a child never overwrites the parent's
     * transcript or event stream in a shared workspace.
     */
    public static String persist(Workspace workspace, RunSnapshot snapshot,
                                 List<ChatMessage> messages, RunEventLog events,
                                 String agentDir) {
        try {
            Path dir = workspace.root().resolve(agentDir);
            Files.createDirectories(dir);
            ObjectNode root = JSON.createObjectNode();
            root.put("id", snapshot.id());
            root.put("task", snapshot.task());
            root.put("workspace", snapshot.workspace());
            root.put("status", snapshot.status());
            root.put("phase", snapshot.phase());
            root.put("phaseLabel", snapshot.phaseLabel());
            root.put("step", snapshot.step());
            root.put("maxSteps", snapshot.maxSteps());
            root.put("finalAnswer", snapshot.finalAnswer());
            root.put("error", snapshot.error());
            root.put("verification", snapshot.verification());
            root.put("startedAt", snapshot.startedAt());
            root.put("finishedAt", snapshot.finishedAt());
            root.put("transcriptPath", snapshot.transcriptPath());
            if (snapshot.parentRunId() != null) {
                root.put("parentRunId", snapshot.parentRunId());
            }
            if (snapshot.forkSeq() != null) {
                root.put("forkSeq", snapshot.forkSeq());
            }
            root.put("usage", JSON.valueToTree(snapshot.usage()));
            root.set("plan", JSON.valueToTree(snapshot.plan()));
            root.set("files", JSON.valueToTree(snapshot.files()));
            ArrayNode messageArray = root.putArray("messages");
            for (ChatMessage message : messages) {
                ObjectNode node = messageArray.addObject();
                node.put("role", message.role().wireName());
                if (message.content() != null) {
                    node.put("content", message.content());
                }
                if (message.toolCallId() != null) {
                    node.put("toolCallId", message.toolCallId());
                }
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("toolCalls");
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode callNode = calls.addObject();
                        callNode.put("id", call.id());
                        callNode.put("name", call.name());
                        callNode.put("arguments", call.arguments());
                    }
                }
            }
            Files.writeString(dir.resolve("transcript.json"), root.toPrettyString(), StandardCharsets.UTF_8);

            Path eventsFile = dir.resolve("events.jsonl");
            boolean existingEvents = Files.isRegularFile(eventsFile);
            long alreadyPersisted = existingEvents ? events.persistedDiskSeq() : 0L;
            List<AgentEvent> delta = existingEvents ? events.after(alreadyPersisted) : events.snapshot();
            if (!delta.isEmpty()) {
                StringBuilder eventLines = new StringBuilder();
                long highest = alreadyPersisted;
                for (AgentEvent event : delta) {
                    eventLines.append(JSON.writeValueAsString(event)).append('\n');
                    if (event.seq() > highest) {
                        highest = event.seq();
                    }
                }
                if (existingEvents) {
                    Files.writeString(eventsFile, eventLines.toString(), StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } else {
                    Files.writeString(eventsFile, eventLines.toString(), StandardCharsets.UTF_8);
                }
                events.markPersistedDiskSeq(highest);
            } else if (!existingEvents) {
                Files.writeString(eventsFile, "", StandardCharsets.UTF_8);
            }

            if (snapshot.finalAnswer() != null && !snapshot.finalAnswer().isBlank()) {
                Files.writeString(dir.resolve("final.md"), snapshot.finalAnswer(), StandardCharsets.UTF_8);
            }
            return agentDir + "/transcript.json";
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Loads a saved workspace. A final run.finished event is the preferred
     * snapshot because it contains the exact terminal state; transcript.json is
     * the fallback for runs persisted before that event was written.
     */
    public static Optional<StoredRun> load(Path workspaceRoot) {
        Path dir = workspaceRoot.resolve(".agent");
        Path transcript = dir.resolve("transcript.json");
        if (!Files.isRegularFile(transcript)) {
            return Optional.empty();
        }
        try {
            List<AgentEvent> events = loadEvents(dir.resolve("events.jsonl"));
            RunSnapshot snapshot = terminalSnapshot(events);
            RunSnapshot fromTranscript = snapshotFromTranscript(transcript, workspaceRoot);
            if (snapshot == null) {
                snapshot = fromTranscript;
            } else if (snapshot.parentRunId() == null && fromTranscript != null
                    && fromTranscript.parentRunId() != null) {
                snapshot = snapshot.withFork(fromTranscript.parentRunId(), fromTranscript.forkSeq());
            }
            if (snapshot == null) {
                return Optional.empty();
            }
            snapshot = normalize(snapshot, workspaceRoot);
            boolean hasFinished = events.stream().anyMatch(event -> "run.finished".equals(event.type()));
            if (!hasFinished) {
                long seq = events.stream().mapToLong(AgentEvent::seq).max().orElse(0L) + 1;
                long timestamp = snapshot.finishedAt() > 0 ? snapshot.finishedAt() : System.currentTimeMillis();
                events.add(new AgentEvent(seq, timestamp, "run.finished", Map.of("snapshot", snapshot)));
            }
            return Optional.of(new StoredRun(snapshot, List.copyOf(events)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Reads the persisted conversation so a follow-up turn can continue with
     * the same history instead of starting from a synthetic task message.
     */
    /** Reads a child transcript written under {@code .agent/subagents/<id>}. */
    public static List<ChatMessage> loadMessages(Path workspaceRoot, String childRunId) {
        return loadFrom(workspaceRoot.resolve(".agent/subagents").resolve(childRunId)
                .resolve("transcript.json"));
    }

    public static List<ChatMessage> loadMessages(Path workspaceRoot) {
        return loadFrom(workspaceRoot.resolve(TRANSCRIPT));
    }

    private static List<ChatMessage> loadFrom(Path transcript) {
        if (!Files.isRegularFile(transcript)) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(transcript.toFile());
            JsonNode messages = root == null ? null : root.get("messages");
            if (messages == null || !messages.isArray()) {
                return List.of();
            }
            List<ChatMessage> result = new ArrayList<>();
            for (JsonNode node : messages) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                String roleName = text(node, "role", "user").toUpperCase(java.util.Locale.ROOT);
                Role role;
                try {
                    role = Role.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String content = text(node, "content", null);
                String toolCallId = text(node, "toolCallId", null);
                String reasoning = text(node, "reasoningContent", null);
                List<ToolCall> calls = new ArrayList<>();
                JsonNode callNodes = node.get("toolCalls");
                if (callNodes != null && callNodes.isArray()) {
                    for (JsonNode call : callNodes) {
                        if (call == null || !call.isObject()) {
                            continue;
                        }
                        String callId = text(call, "id", null);
                        String name = text(call, "name", null);
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        calls.add(new ToolCall(callId, name, text(call, "arguments", "{}")));
                    }
                }
                result.add(new ChatMessage(role, content, toolCallId, calls, reasoning));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static List<AgentEvent> loadEvents(Path path) throws IOException {
        List<AgentEvent> events = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return events;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && events.size() < MAX_EVENTS) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    AgentEvent event = JSON.readValue(line, AgentEvent.class);
                    if (event != null && event.type() != null) {
                        events.add(event);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // A truncated final line should not hide the rest of the run.
                }
            }
        }
        return events;
    }

    private static RunSnapshot terminalSnapshot(List<AgentEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            AgentEvent event = events.get(i);
            if (!"run.finished".equals(event.type()) || event.data() == null) {
                continue;
            }
            Object raw = event.data().get("snapshot");
            if (raw == null) {
                continue;
            }
            try {
                return JSON.treeToValue(JSON.valueToTree(raw), RunSnapshot.class);
            } catch (RuntimeException | IOException ignored) {
                // fall through to transcript.json
            }
        }
        return null;
    }

    private static RunSnapshot snapshotFromTranscript(Path transcript, Path workspaceRoot) throws IOException {
        JsonNode root = JSON.readTree(transcript.toFile());
        if (root == null || !root.isObject()) {
            return null;
        }
        String id = text(root, "id", "run-" + workspaceRoot.getFileName());
        String task = text(root, "task", "Recovered session");
        String workspace = text(root, "workspace", workspaceRoot.getFileName().toString());
        String status = text(root, "status", "FAILED").toUpperCase();
        String phase = text(root, "phase", AgentPhase.CHECKPOINT.id());
        String phaseLabel = text(root, "phaseLabel", AgentPhase.CHECKPOINT.label());
        int step = root.path("step").asInt(0);
        int maxSteps = root.path("maxSteps").asInt(10);
        String finalAnswer = text(root, "finalAnswer", null);
        String error = text(root, "error", null);
        String verification = text(root, "verification", null);
        long startedAt = root.path("startedAt").asLong(0L);
        if (startedAt <= 0) {
            try {
                FileTime modified = Files.getLastModifiedTime(transcript);
                startedAt = modified.toMillis();
            } catch (IOException ignored) {
                startedAt = System.currentTimeMillis();
            }
        }
        long finishedAt = root.path("finishedAt").asLong(0L);
        List<PlanItem> plan = readList(root.get("plan"), new TypeReference<>() { });
        if (plan == null || plan.isEmpty()) {
            plan = RunState.defaultPlan();
        }
        List<String> files = readList(root.get("files"), new TypeReference<>() { });
        if (files == null) {
            files = List.of();
        }
        String parentRunId = text(root, "parentRunId", null);
        Long forkSeq = root.has("forkSeq") && !root.get("forkSeq").isNull()
                ? root.get("forkSeq").asLong()
                : null;
        Usage usage = Usage.ZERO;
        if (root.has("usage") && !root.get("usage").isNull()) {
            try {
                usage = JSON.treeToValue(root.get("usage"), Usage.class);
            } catch (RuntimeException | IOException ignored) {
                usage = Usage.ZERO;
            }
        }
        return new RunSnapshot(id, task, workspace, status, phase, phaseLabel, step, maxSteps,
                plan, files, usage, finalAnswer, error, verification, startedAt, finishedAt, TRANSCRIPT,
                parentRunId, forkSeq);
    }

    private static RunSnapshot normalize(RunSnapshot snapshot, Path workspaceRoot) {
        String id = snapshot.id() == null || snapshot.id().isBlank()
                ? "run-" + workspaceRoot.getFileName()
                : snapshot.id();
        String task = snapshot.task() == null || snapshot.task().isBlank()
                ? "Recovered session"
                : snapshot.task();
        String workspace = snapshot.workspace() == null || snapshot.workspace().isBlank()
                ? workspaceRoot.getFileName().toString()
                : snapshot.workspace();
        String transcriptPath = snapshot.transcriptPath() == null || snapshot.transcriptPath().isBlank()
                ? TRANSCRIPT
                : snapshot.transcriptPath();
        String status = snapshot.status() == null ? "FAILED" : snapshot.status().toUpperCase();
        String phase = snapshot.phase() == null ? AgentPhase.CHECKPOINT.id() : snapshot.phase();
        String phaseLabel = snapshot.phaseLabel() == null ? AgentPhase.CHECKPOINT.label() : snapshot.phaseLabel();
        List<PlanItem> plan = snapshot.plan() == null || snapshot.plan().isEmpty()
                ? RunState.defaultPlan()
                : snapshot.plan();
        List<String> files = snapshot.files() == null ? List.of() : snapshot.files();
        Usage usage = snapshot.usage() == null ? Usage.ZERO : snapshot.usage();
        boolean terminal = switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "MAX_STEPS", "LOOP_DETECTED", "BUDGET_EXCEEDED" -> true;
            default -> false;
        };
        if (!terminal) {
            return new RunSnapshot(id, task, workspace, RunStatus.FAILED.name(), phase, phaseLabel,
                    snapshot.step(), snapshot.maxSteps(), plan, files, usage, snapshot.finalAnswer(),
                    "Run was interrupted by a server restart", snapshot.verification(),
                    snapshot.startedAt(), System.currentTimeMillis(), transcriptPath,
                    snapshot.parentRunId(), snapshot.forkSeq());
        }
        return new RunSnapshot(id, task, workspace, status, phase, phaseLabel, snapshot.step(),
                snapshot.maxSteps(), plan, files, usage, snapshot.finalAnswer(), snapshot.error(),
                snapshot.verification(), snapshot.startedAt(), snapshot.finishedAt(), transcriptPath,
                snapshot.parentRunId(), snapshot.forkSeq());
    }

    private static <T> List<T> readList(JsonNode node, TypeReference<List<T>> type) {
        if (node == null || !node.isArray()) {
            return null;
        }
        try {
            return JSON.convertValue(node, type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }
}
