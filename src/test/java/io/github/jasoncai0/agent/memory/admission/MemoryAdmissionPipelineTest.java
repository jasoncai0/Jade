package io.github.jasoncai0.agent.memory.admission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.agent.RunEventLog;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.LlmSettings;
import io.github.jasoncai0.agent.config.MysqlSettings;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.memory.MemoryCandidate;
import io.github.jasoncai0.agent.memory.MemoryHealth;
import io.github.jasoncai0.agent.memory.MemoryItem;
import io.github.jasoncai0.agent.memory.MemoryMode;
import io.github.jasoncai0.agent.memory.MemoryPolicy;
import io.github.jasoncai0.agent.memory.MemoryScope;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.MemorySettings;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.web.WebServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAdmissionPipelineTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MemoryScope SCOPE = new MemoryScope("u1", "jade", "project-jade", "jade", "run-test");

    @TempDir
    Path temp;

    @Test
    void blocksSecretsDeterministicallyBeforeCallingLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        LlmClient spyLlm = request -> {
            llmCalls.incrementAndGet();
            throw new AssertionError("LLM must not be called when user message contains a secret");
        };
        FakeMemoryService mem = new FakeMemoryService();
        MemoryAdmissionPipeline pipeline = new MemoryAdmissionPipeline(
                mem, MemorySettings.defaults(), spyLlm, new InMemoryPendingMemoryStore());
        RunEventLog log = new RunEventLog("run-sec", null);

        AdmissionContext ctx = new AdmissionContext(
                "run-sec",
                "setup env",
                List.of("以后记住 DEEPSEEK_API_KEY=REDACTED_TEST_SECRET"),
                "ok",
                "passed",
                List.of(),
                SCOPE
        );
        List<MemoryWritePolicy.Resolution> res = pipeline.processTurnSync(ctx, log);
        assertEquals(0, llmCalls.get());
        assertEquals(MemoryWritePolicy.Action.DISCARD, res.get(0).action());
        assertEquals("sensitive_filtered", res.get(0).reasonCode());
        assertTrue(mem.written.isEmpty());
        // Audit events must never leak raw secret text
        for (AgentEvent ev : log.snapshot()) {
            assertFalse(ev.data().toString().contains("REDACTED_TEST_SECRET"));
        }
    }

    @Test
    void rejectsFabricatedQuotesAndAutoWritesHighConfidenceUserPreferenceWithInferFalse() {
        FakeMemoryService mem = new FakeMemoryService();
        InMemoryPendingMemoryStore pending = new InMemoryPendingMemoryStore();

        // 1) Fabricated quote not present in userMessages[0] -> rejected
        LlmClient hallucinatingLlm = emitMemoryLlm("""
                {
                  "write": true,
                  "kind": "preference",
                  "scope": "project",
                  "statement": "Jade 仓库默认使用 Gradle 构建。",
                  "evidence": [{"source": "user", "messageIndex": 0, "quote": "以后全部改用 Gradle"}],
                  "confidence": 0.95,
                  "reason": "fabricated",
                  "supersedes": null
                }
                """);
        MemoryAdmissionPipeline badPipeline = new MemoryAdmissionPipeline(
                mem, MemorySettings.defaults(), hallucinatingLlm, pending);
        RunEventLog log1 = new RunEventLog("run-fab", null);
        AdmissionContext ctx1 = new AdmissionContext(
                "run-fab", "build", List.of("帮我看一下 pom.xml 里的依赖版本"), "done", "ok", List.of(), SCOPE);
        var r1 = badPipeline.processTurnSync(ctx1, log1);
        assertEquals(MemoryWritePolicy.Action.DISCARD, r1.get(0).action());
        assertEquals("user_quote_mismatch", r1.get(0).reasonCode());
        assertTrue(mem.written.isEmpty());
        assertTrue(pending.listPending("project-jade", 10).isEmpty());

        // 2) Verbatim user quote + confidence 0.92 + preference -> auto-write with infer=false
        LlmClient groundedLlm = emitMemoryLlm("""
                {
                  "write": true,
                  "kind": "preference",
                  "scope": "project",
                  "statement": "Jade 仓库 Java 代码统一使用 4 空格缩进。",
                  "evidence": [{"source": "user", "messageIndex": 0, "quote": "以后这个仓库 Java 代码统一用 4 空格缩进"}],
                  "confidence": 0.92,
                  "reason": "explicit coding style rule",
                  "supersedes": null
                }
                """);
        MemoryAdmissionPipeline goodPipeline = new MemoryAdmissionPipeline(
                mem, MemorySettings.defaults(), groundedLlm, pending);
        RunEventLog log2 = new RunEventLog("run-ok", null);
        AdmissionContext ctx2 = new AdmissionContext(
                "run-ok", "style", List.of("以后这个仓库 Java 代码统一用 4 空格缩进，帮我格式化 Main.java"),
                "formatted", "javac passed", List.of("Main.java"), SCOPE);
        var r2 = goodPipeline.processTurnSync(ctx2, log2);
        assertEquals(MemoryWritePolicy.Action.AUTO_WRITE, r2.get(0).action());
        assertEquals(1, mem.written.size());
        assertFalse(mem.written.get(0).infer(), "Hybrid admission must write normalized statement with infer=false");
        assertEquals("Jade 仓库 Java 代码统一使用 4 空格缩进。", mem.written.get(0).text());
    }

    @Test
    void routesPitfallAndNonUserEvidenceToPendingAndSupportsSupersedesAndTtlRecall() {
        FakeMemoryService mem = new FakeMemoryService();
        long now = System.currentTimeMillis();
        // Seed an older conflicting active preference + an expired command_recipe
        mem.stored.add(new MemoryItem(
                "mem-old-indent",
                "Jade 仓库 Java 代码默认使用 2 空格缩进。",
                0.80,
                "preference",
                "project",
                "project-jade",
                "user",
                "",
                Map.of("status", "active", "confidence", 0.85, "created_at_ms", now - 86_400_000L * 20)
        ));
        mem.stored.add(new MemoryItem(
                "mem-expired-cmd",
                "旧构建命令 mvn test-compile",
                0.95,
                "command_recipe",
                "project",
                "project-jade",
                "user",
                "",
                Map.of("status", "active", "expires_at_ms", now - 1_000L)
        ));

        InMemoryPendingMemoryStore pending = new InMemoryPendingMemoryStore();
        // Pitfall with high confidence (0.91) still goes to Pending per §16.13.2
        LlmClient pitfallLlm = emitMemoryLlm("""
                {
                  "write": true,
                  "kind": "pitfall",
                  "scope": "project",
                  "statement": "Mockito inline 在新 JDK 上不可用，需改用 subclass mock maker。",
                  "evidence": [{"source": "verification", "messageIndex": 0, "quote": "Mockito inline failed"}],
                  "confidence": 0.91,
                  "reason": "verified pitfall",
                  "supersedes": null
                }
                """);
        MemoryAdmissionPipeline pipeline = new MemoryAdmissionPipeline(
                mem, MemorySettings.defaults(), pitfallLlm, pending);
        AdmissionContext ctx = new AdmissionContext(
                "run-pit", "fix tests", List.of("跑一下单测看看为什么挂了"),
                "fixed", "Mockito inline failed; switched to subclass", List.of(), SCOPE);
        var res = pipeline.processTurnSync(ctx, new RunEventLog("run-pit", null));
        assertEquals(MemoryWritePolicy.Action.PENDING, res.get(0).action());
        assertEquals(1, pending.listPending("project-jade", 10).size());

        // Approve pending item -> promoted to Mem0 with infer=false
        PendingMemoryItem item = pending.listPending("project-jade", 10).get(0);
        PendingMemoryItem approved = pipeline.approvePending(item.id(), SCOPE);
        assertEquals("approved", approved.status());
        assertEquals(1, mem.written.size());
        assertFalse(mem.written.get(0).infer());

        // Recall filtering: expired item and superseded item must be excluded
        MemoryItem replacement = new MemoryItem(
                "mem-new-indent",
                "Jade 仓库 Java 代码默认使用 4 空格缩进。",
                0.80,
                "preference",
                "project",
                "project-jade",
                "user",
                "",
                Map.of("status", "active", "confidence", 0.95, "created_at_ms", now, "supersedes", "mem-old-indent")
        );
        List<MemoryItem> selected = MemoryPolicy.select(
                List.of(mem.stored.get(0), mem.stored.get(1), replacement), SCOPE, now);
        assertEquals(1, selected.size());
        assertEquals("mem-new-indent", selected.get(0).id());
    }

    @Test
    void exposesPendingInboxEndpointsAndMySqlStoreWhenAvailable() throws Exception {
        MysqlSettings mysql = probeLocalMysql();
        PendingMemoryStore store = mysql != null
                ? new MySqlPendingMemoryStore(mysql)
                : new InMemoryPendingMemoryStore();
        try {
            String uniqueProject = "proj-api-" + System.nanoTime();
            AppConfig config = new AppConfig(
                    temp,
                    temp.resolve("skills"),
                    temp.resolve("workspaces"),
                    new LlmSettings("sk-dummy", "http://127.0.0.1:9", "deepseek-chat", 30, 1024),
                    new AgentSettings(5, 4, 40_000, 30, true, PermissionMode.BALANCED, 0, false, 30_000, true, false),
                    new ServerSettings("127.0.0.1", 0, ""),
                    mysql,
                    new MemorySettings(MemoryMode.OFF, "mem0-oss", "http://127.0.0.1:9", "",
                            "u1", 5, 500, 1000, true, uniqueProject,
                            AdmissionMode.HYBRID, 0.85, 0.60, 3, 2000),
                    false
            );
            try (AgentService service = new AgentService(config);
                 WebServer web = new WebServer(service, config.server())) {
                String pid = "pmem-test-" + System.nanoTime();
                service.memoryPipeline().pendingStore().enqueue(new PendingMemoryItem(
                        pid, uniqueProject, "demo", "project", "decision",
                        "所有分页接口使用 keyset 游标分页。", 0.78, "user stated pagination rule",
                        "user", 0, "分页统一走 keyset", "", "run-123",
                        "pending", "", System.currentTimeMillis(), System.currentTimeMillis()
                ));
                web.start();
                HttpClient http = HttpClient.newHttpClient();

                // GET /api/memory/pending
                HttpResponse<String> getResp = http.send(
                        HttpRequest.newBuilder(URI.create(web.url() + "/api/memory/pending")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, getResp.statusCode());
                JsonNode listData = JSON.readTree(getResp.body()).path("data");
                assertTrue(listData.isArray() && listData.size() >= 1);
                assertEquals(pid, listData.get(0).path("id").asText());

                // POST /api/memory/pending/{id}/reject
                HttpResponse<String> rejResp = http.send(
                        HttpRequest.newBuilder(URI.create(web.url() + "/api/memory/pending/" + pid + "/reject"))
                                .header("Content-Type", "application/json")
                                .header("Origin", web.url())
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, rejResp.statusCode());
                assertEquals("rejected", JSON.readTree(rejResp.body()).path("data").path("status").asText());
            }
        } finally {
            store.close();
        }
    }

    private static MysqlSettings probeLocalMysql() {
        MysqlSettings candidate = new MysqlSettings(MysqlSettings.DEFAULT_URL, "jade", "jade_dev_password");
        try (Connection ignored = DriverManager.getConnection(candidate.url(), candidate.user(), candidate.password())) {
            return candidate;
        } catch (Exception e) {
            Assumptions.assumeTrue(true);
            return null;
        }
    }

    private static LlmClient emitMemoryLlm(String jsonArgs) {
        return new LlmClient() {
            @Override
            public ChatResult chat(ChatRequest request) {
                return new ChatResult(
                        "",
                        "",
                        List.of(new ToolCall("call-1", LlmMemoryAdmission.TOOL_NAME, jsonArgs)),
                        "tool_calls",
                        new Usage(30, 20, 50)
                );
            }
        };
    }

    private static final class FakeMemoryService implements MemoryService {
        final List<MemoryCandidate> written = new CopyOnWriteArrayList<>();
        final List<MemoryItem> stored = new ArrayList<>();
        final List<String> forgotten = new CopyOnWriteArrayList<>();

        @Override
        public MemoryMode mode() {
            return MemoryMode.ON;
        }

        @Override
        public MemoryHealth health() {
            return new MemoryHealth(true, "fake", "on", "http://fake", "connected", 1);
        }

        @Override
        public MemoryScope scope(Path workspace, String sessionId) {
            return SCOPE;
        }

        @Override
        public List<MemoryItem> recall(String query, MemoryScope scope, int limit) {
            return List.copyOf(stored);
        }

        @Override
        public String remember(MemoryCandidate candidate, MemoryScope scope) {
            written.add(candidate);
            return "mem-" + written.size();
        }

        @Override
        public void forget(String id) {
            forgotten.add(id);
        }
    }
}
