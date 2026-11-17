package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.LlmException;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loop must stream advisory deltas without polluting the durable log. */
class AgentLoopStreamingTest {
    private static final AgentSettings SETTINGS = new AgentSettings(
            4, 4, 20_000, 30, true, PermissionMode.BYPASS, 0, false, 0, true);

    @TempDir
    Path temp;

    @Test
    void streamsDeltasButPersistsOnlyTheFinalAssistant() throws Exception {
        StreamingLlm llm = new StreamingLlm(listener -> {
            listener.onText("Hello");
            listener.onText(" world");
        }, new ChatResult("Hello world", null, List.of(), "stop", new Usage(9, 4, 13)));
        LiveDeltaBus bus = new LiveDeltaBus();
        Harness harness = harness(llm);

        RunSnapshot snapshot = harness.loop().withLiveDeltas(bus).run(
                "say hello", harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals("Hello world", snapshot.finalAnswer());
        List<LiveDeltaBus.Delta> deltas = bus.after(0);
        assertEquals(1, deltas.size(), "two immediate chunks coalesce into one delta");
        assertEquals("Hello world", deltas.get(0).text());
        assertEquals(1, count(harness.events(), "assistant"));
        assertEquals(0, count(harness.events(), "assistant.delta"));
        assertEquals(1, llm.requests.size());
    }

    @Test
    void longStreamsSplitAtTheChunkThreshold() throws Exception {
        StringBuilder streamed = new StringBuilder();
        StreamingLlm llm = new StreamingLlm(listener -> {
            for (int i = 0; i < 40; i++) {
                String piece = "0123456789abcdef"; // 16 chars -> 640 chars total
                streamed.append(piece);
                listener.onText(piece);
            }
        }, new ChatResult(streamed.toString(), null, List.of(), "stop", new Usage(9, 4, 13)));
        LiveDeltaBus bus = new LiveDeltaBus();
        Harness harness = harness(llm);

        harness.loop().withLiveDeltas(bus).run(
                "long answer", harness.workspace(), harness.state(), harness.events(), harness.control());

        List<LiveDeltaBus.Delta> deltas = bus.after(0);
        assertTrue(deltas.size() >= 2, "640 chars must not arrive as one frame: " + deltas.size());
        String joined = deltas.stream().map(LiveDeltaBus.Delta::text).reduce("", String::concat);
        assertEquals(streamed.toString(), joined);
    }

    @Test
    void failedStreamsPublishNothing() throws Exception {
        StreamingLlm llm = new StreamingLlm(listener -> {
            listener.onText("half an ans");
            throw new LlmException("upstream exploded", 500, null, null);
        }, null);
        LiveDeltaBus bus = new LiveDeltaBus();
        Harness harness = harness(llm);

        RunSnapshot snapshot = harness.loop().withLiveDeltas(bus).run(
                "say hello", harness.workspace(), harness.state(), harness.events(), harness.control());

        assertEquals("FAILED", snapshot.status());
        assertTrue(bus.after(0).isEmpty(), "partial text must not be published as a delta");
        assertEquals(0, count(harness.events(), "assistant"));
    }

    @Test
    void worksWithoutALiveBus() throws Exception {
        StreamingLlm llm = new StreamingLlm(listener -> listener.onText("Hi"), new ChatResult(
                "Hi", null, List.of(), "stop", new Usage(3, 1, 4)));
        Harness harness = harness(llm);

        RunSnapshot snapshot = harness.loop().run("say hi", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals("Hi", snapshot.finalAnswer());
        assertFalse(snapshot.status().isEmpty());
    }

    private static long count(RunEventLog events, String type) {
        return events.snapshot().stream().filter(event -> event.type().equals(type)).count();
    }

    private Harness harness(LlmClient llm) throws Exception {
        Path root = temp.resolve("ws-" + System.nanoTime());
        Files.createDirectories(root);
        Path skills = temp.resolve("skills-" + System.nanoTime());
        Files.createDirectories(skills);
        return new Harness(
                new AgentLoop(llm, BuiltInTools.registry(), SkillRegistry.load(skills), SETTINGS, 1024),
                Workspace.open(root),
                new RunState("run-stream", "task", "ws", SETTINGS.maxSteps()),
                new RunEventLog(),
                new RunControl());
    }

    private record Harness(AgentLoop loop, Workspace workspace, RunState state,
                           RunEventLog events, RunControl control) {
    }

    /** Streaming fake: emits through the listener, then returns the final result. */
    private static final class StreamingLlm implements LlmClient {
        private final Consumer<StreamListener> body;
        private final ChatResult result;
        private final List<ChatRequest> requests = new java.util.ArrayList<>();

        private StreamingLlm(Consumer<StreamListener> body, ChatResult result) {
            this.body = body;
            this.result = result;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            throw new UnsupportedOperationException("streaming path only");
        }

        @Override
        public ChatResult stream(ChatRequest request, StreamListener listener) {
            requests.add(request);
            body.accept(listener);
            if (result == null) {
                throw new LlmException("scripted stream did not finish", 0, null, null);
            }
            return result;
        }
    }
}
