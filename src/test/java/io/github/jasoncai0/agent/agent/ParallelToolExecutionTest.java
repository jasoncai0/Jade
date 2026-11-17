package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelToolExecutionTest {
    private static final AgentSettings SETTINGS = new AgentSettings(
            8, 8, 200_000, 100, true, PermissionMode.BYPASS, 0, false, 30_000, true);

    @TempDir
    Path temp;

    @Test
    void fourReadsReachTheBarrierTogether() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(4);
        ToolRegistry registry = new ToolRegistry();
        List<Probe> probes = new ArrayList<>();
        for (String name : List.of("read_a", "read_b", "read_c", "read_d")) {
            Probe probe = new Probe(name, true, barrier, 0);
            probes.add(probe);
            registry.register(probe);
        }
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(new ToolCall("c" + i, "read_" + (char) ('a' + i), "{}"));
        }

        Harness harness = harness(registry, calls);
        RunSnapshot snapshot = harness.loop().run("go", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertTrue(probes.stream().allMatch(probe -> probe.failures.get() == 0),
                "barrier timed out, so the tools did not overlap");
    }

    @Test
    void fiveReadsNeverExceedFourWorkers() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            String name = "read_" + i;
            registry.register(new Tool() {
                @Override
                public ToolSpec spec() {
                    return specOf(name);
                }

                @Override
                public ToolResult execute(ToolContext context, Map<String, Object> args) {
                    int now = active.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                    return ToolResult.ok("ok");
                }

                @Override
                public boolean concurrencySafe() {
                    return true;
                }
            });
        }
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            calls.add(new ToolCall("c" + i, "read_" + i, "{}"));
        }

        Harness harness = harness(registry, calls);
        RunSnapshot snapshot = harness.loop().run("go", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertTrue(peak.get() <= 4, "peak concurrency was " + peak.get());
        assertTrue(peak.get() >= 2, "reads did not overlap");
    }

    @Test
    void oneMutatingCallKeepsTheWholeBatchSerial() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        registry.register(delayed("read_a", true, active, peak));
        registry.register(delayed("read_b", true, active, peak));
        registry.register(delayed("write_c", false, active, peak));

        Harness harness = harness(registry, List.of(
                new ToolCall("c1", "read_a", "{}"),
                new ToolCall("c2", "write_c", "{}"),
                new ToolCall("c3", "read_b", "{}")));
        RunSnapshot snapshot = harness.loop().run("go", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals(1, peak.get());
    }

    @Test
    void resultsAreCommittedInModelOrder() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("a", "b", "c", "d")) {
            registry.register(new Probe(name, true, null, 40));
        }
        Harness harness = harness(registry, List.of(
                new ToolCall("c1", "a", "{}"),
                new ToolCall("c2", "b", "{}"),
                new ToolCall("c3", "c", "{}"),
                new ToolCall("c4", "d", "{}")));
        harness.loop().run("go", harness.workspace(), harness.state(),
                harness.events(), harness.control());

        List<String> ended = harness.events().snapshot().stream()
                .filter(event -> event.type().equals("tool.end"))
                .map(event -> String.valueOf(event.data().get("name")))
                .toList();
        assertEquals(List.of("a", "b", "c", "d"), ended);
    }

    @Test
    void invalidArgumentsDoNotConsumeAWorkerSlot() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        for (String name : List.of("read_a", "read_b", "read_c", "read_d")) {
            registry.register(new Probe(name, true, null, 80));
        }
        Harness harness = harness(registry, List.of(
                new ToolCall("c1", "read_a", "{}"),
                new ToolCall("c2", "read_b", "{}"),
                new ToolCall("c3", "read_c", "{}"),
                new ToolCall("c4", "read_d", "{}"),
                new ToolCall("c5", "read_a", "not-json")));
        RunSnapshot snapshot = harness.loop().run("go", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        long errors = harness.events().snapshot().stream()
                .filter(event -> event.type().equals("tool.end"))
                .filter(event -> Boolean.FALSE.equals(event.data().get("ok")))
                .count();
        assertEquals(1, errors);
    }

    @Test
    void deniedCallDoesNotConsumeAWorkerSlot() throws Exception {
        AgentSettings readOnly = new AgentSettings(
                8, 8, 200_000, 100, true, PermissionMode.READ_ONLY, 0, false, 30_000, true);
        ToolRegistry registry = new ToolRegistry();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        for (String name : List.of("read_file", "list_files", "search_text", "load_skill")) {
            registry.register(delayed(name, true, active, peak));
        }
        registry.register(delayed("write_file", false, active, peak));

        Harness harness = harness(registry, List.of(
                new ToolCall("c1", "read_file", "{}"),
                new ToolCall("c2", "list_files", "{}"),
                new ToolCall("c3", "search_text", "{}"),
                new ToolCall("c4", "load_skill", "{}"),
                new ToolCall("c5", "write_file", "{}")), readOnly);
        RunSnapshot snapshot = harness.loop().run("go", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals(4, peak.get());
        assertTrue(harness.events().snapshot().stream()
                .filter(event -> event.type().equals("tool.end"))
                .anyMatch(event -> "write_file".equals(event.data().get("name"))
                        && Boolean.FALSE.equals(event.data().get("ok"))
                        && String.valueOf(event.data().get("output")).contains("read-only")));
    }

    @Test
    void workerExceptionBecomesAToolError() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Probe("read_a", true, null, 0));
        registry.register(new Tool() {
            @Override
            public ToolSpec spec() {
                return specOf("boom");
            }

            @Override
            public ToolResult execute(ToolContext context, Map<String, Object> args) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean concurrencySafe() {
                return true;
            }
        });
        Harness harness = harness(registry, List.of(
                new ToolCall("c1", "read_a", "{}"),
                new ToolCall("c2", "boom", "{}")));
        RunSnapshot snapshot = harness.loop().run("go", harness.workspace(),
                harness.state(), harness.events(), harness.control());

        assertEquals("SUCCEEDED", snapshot.status());
        assertTrue(harness.events().snapshot().stream()
                .filter(event -> event.type().equals("tool.end"))
                .anyMatch(event -> "boom".equals(event.data().get("name"))
                        && Boolean.FALSE.equals(event.data().get("ok"))
                        && String.valueOf(event.data().get("output")).contains("boom")));
    }

    @Test
    void metadataIsNotInferredFromMutatesWorkspace() {
        Probe read = new Probe("read_a", true, null, 0);
        Probe write = new Probe("write_a", false, null, 0);
        assertFalse(read.mutatesWorkspace());
        assertTrue(read.concurrencySafe());
        assertTrue(write.mutatesWorkspace());
        assertFalse(write.concurrencySafe());
    }

    private static Tool delayed(String name, boolean safe, AtomicInteger active, AtomicInteger peak) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return specOf(name);
            }

            @Override
            public ToolResult execute(ToolContext context, Map<String, Object> args) {
                int now = active.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                return ToolResult.ok("ok");
            }

            @Override
            public boolean concurrencySafe() {
                return safe;
            }

            @Override
            public boolean mutatesWorkspace() {
                return !safe;
            }
        };
    }

    private static ToolSpec specOf(String name) {
        return new ToolSpec(name, "test tool", Map.of("type", "object", "properties", Map.of()), false);
    }

    private Harness harness(ToolRegistry registry, List<ToolCall> calls) throws Exception {
        return harness(registry, calls, SETTINGS);
    }

    private Harness harness(ToolRegistry registry, List<ToolCall> calls,
                            AgentSettings settings) throws Exception {
        Path root = temp.resolve("ws-" + System.nanoTime());
        Files.createDirectories(root.resolve(".agent-build/classes"));
        Path skills = temp.resolve("skills-" + System.nanoTime());
        Files.createDirectories(skills);
        ScriptedLlm llm = new ScriptedLlm(calls);
        return new Harness(
                new AgentLoop(llm, registry, SkillRegistry.load(skills), settings, 1024),
                Workspace.open(root),
                new RunState("run-parallel", "go", "ws", settings.maxSteps()),
                new RunEventLog(),
                new RunControl());
    }

    private record Harness(AgentLoop loop, Workspace workspace, RunState state,
                           RunEventLog events, RunControl control) {
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> responses = new ArrayDeque<>();

        private ScriptedLlm(List<ToolCall> calls) {
            responses.add(new ChatResult("", null, calls, "tool_calls", Usage.ZERO));
            responses.add(new ChatResult("done", null, List.of(), "stop", new Usage(1, 1, 2)));
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            ChatResult next = responses.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response left");
            }
            return next;
        }
    }

    private static final class Probe implements Tool {
        private final String name;
        private final boolean safe;
        private final CyclicBarrier barrier;
        private final long sleepMs;
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        private Probe(String name, boolean safe, CyclicBarrier barrier, long sleepMs) {
            this.name = name;
            this.safe = safe;
            this.barrier = barrier;
            this.sleepMs = sleepMs;
        }

        @Override
        public ToolSpec spec() {
            return specOf(name);
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            try {
                if (barrier != null) {
                    barrier.await(3, TimeUnit.SECONDS);
                } else if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            } catch (Exception e) {
                failures.incrementAndGet();
                return ToolResult.error("probe failed: " + e.getMessage());
            }
            peak.accumulateAndGet(1, Math::max);
            return ToolResult.ok("ok");
        }

        @Override
        public boolean concurrencySafe() {
            return safe;
        }

        @Override
        public boolean mutatesWorkspace() {
            return !safe;
        }
    }
}
