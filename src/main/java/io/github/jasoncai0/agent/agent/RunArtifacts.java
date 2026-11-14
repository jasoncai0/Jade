package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A run leaves a readable transcript plus the raw event stream in .agent/.
 */
public final class RunArtifacts {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RunArtifacts() {
    }

    public static String persist(Workspace workspace, RunSnapshot snapshot,
                                 List<ChatMessage> messages, RunEventLog events) {
        try {
            Path dir = workspace.root().resolve(".agent");
            Files.createDirectories(dir);
            ObjectNode root = JSON.createObjectNode();
            root.put("id", snapshot.id());
            root.put("task", snapshot.task());
            root.put("status", snapshot.status());
            root.put("finalAnswer", snapshot.finalAnswer());
            root.put("verification", snapshot.verification());
            root.put("startedAt", snapshot.startedAt());
            root.put("finishedAt", snapshot.finishedAt());
            root.put("usage", JSON.valueToTree(snapshot.usage()));
            root.set("plan", JSON.valueToTree(snapshot.plan()));
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

            StringBuilder eventLines = new StringBuilder();
            for (AgentEvent event : events.snapshot()) {
                eventLines.append(JSON.writeValueAsString(event)).append('\n');
            }
            Files.writeString(dir.resolve("events.jsonl"), eventLines.toString(), StandardCharsets.UTF_8);

            if (snapshot.finalAnswer() != null && !snapshot.finalAnswer().isBlank()) {
                Files.writeString(dir.resolve("final.md"), snapshot.finalAnswer(), StandardCharsets.UTF_8);
            }
            return ".agent/transcript.json";
        } catch (IOException e) {
            return null;
        }
    }
}
