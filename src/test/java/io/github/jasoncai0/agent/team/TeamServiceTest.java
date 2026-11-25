package io.github.jasoncai0.agent.team;

import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.LlmSettings;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.permission.PermissionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TeamServiceTest {
    @TempDir
    Path temp;

    @Test
    void waitTimeoutSeparatesRunningMembersFromCompletedOnes() throws Exception {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        Path workspaces = Files.createDirectories(temp.resolve("workspaces"));
        AgentSettings settings = new AgentSettings(4, 4, 20_000, 10, false,
                PermissionMode.BYPASS, 0, false, 0, true, false);
        AppConfig config = new AppConfig(temp, skills, workspaces,
                new LlmSettings("sk-test", "http://127.0.0.1:1", "fake", 5, 1024),
                settings, new ServerSettings("127.0.0.1", 0), null, null, true);
        AtomicBoolean hold = new AtomicBoolean(false);
        CountDownLatch release = new CountDownLatch(1);
        LlmClient llm = request -> {
            if (hold.get()) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test latch timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e);
                }
            }
            return new ChatResult("done", null, List.of(), "stop", Usage.ZERO);
        };
        try (AgentService service = new AgentService(config, llm)) {
            var rootSnapshot = service.start("root task", "team-timeout-test");
            assertNotNull(service.await(rootSnapshot.id(), Duration.ofSeconds(5)));
            AgentRun root = service.require(rootSnapshot.id());
            TeamService team = service.team();
            hold.set(true);
            try {
                TeamMember member = team.spawn(root, "slow", "slow task");
                var waiting = team.waitFor(root, List.of("slow"), 150);
                assertTrue(Boolean.TRUE.equals(waiting.get("timedOut")), String.valueOf(waiting));
                @SuppressWarnings("unchecked")
                List<String> still = (List<String>) waiting.get("stillRunning");
                @SuppressWarnings("unchecked")
                List<String> queued = (List<String>) waiting.get("queued");
                assertTrue(still.contains("slow") || queued.contains("slow"), String.valueOf(waiting));
                @SuppressWarnings("unchecked")
                List<String> completed = (List<String>) waiting.get("completed");
                assertTrue(completed.isEmpty(), String.valueOf(waiting));
            } finally {
                hold.set(false);
                release.countDown();
            }
            var done = team.waitFor(root, List.of("slow"), 10_000);
            assertFalse(Boolean.TRUE.equals(done.get("timedOut")), String.valueOf(done));
            @SuppressWarnings("unchecked")
            List<String> completed = (List<String>) done.get("completed");
            assertTrue(completed.contains("slow"), String.valueOf(done));
        }
    }

    @Test
    void spawnsWritableMemberAndWaitsForIt() throws Exception {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        Path workspaces = Files.createDirectories(temp.resolve("workspaces"));
        AgentSettings settings = new AgentSettings(4, 4, 20_000, 10, false,
                PermissionMode.BYPASS, 0, false, 0, true, false);
        AppConfig config = new AppConfig(temp, skills, workspaces,
                new LlmSettings("sk-test", "http://127.0.0.1:1", "fake", 5, 1024),
                settings, new ServerSettings("127.0.0.1", 0), null, null, true);
        LlmClient llm = request -> new ChatResult("done", null, List.of(), "stop", Usage.ZERO);
        try (AgentService service = new AgentService(config, llm)) {
            var rootSnapshot = service.start("root task", "team-test");
            assertNotNull(service.await(rootSnapshot.id(), Duration.ofSeconds(5)));
            AgentRun root = service.require(rootSnapshot.id());
            TeamService team = service.team();
            assertTrue(team.enabledFor(root));

            TeamTask task = team.createTask(root, "Implement", "write one file", List.of(), List.of("src"));
            assertEquals("task-1", task.id());

            TeamMember member = team.spawn(root, "worker", "Do the task");
            assertNotNull(member);
            var waited = team.waitFor(root, List.of("worker"), 10_000);
            assertFalse(Boolean.TRUE.equals(waited.get("timedOut")), String.valueOf(waited));
            assertNull(member.lastError(), String.valueOf(member.lastError()));
            assertEquals("done", member.lastResult());

            team.send(root, "worker", "follow up");
            var second = team.waitFor(root, List.of("worker"), 10_000);
            assertFalse(Boolean.TRUE.equals(second.get("timedOut")), String.valueOf(second));
            assertEquals("done", member.lastResult());
        }
    }
    @Test
    void waitReturnsPendingApprovalInsteadOfBlocking() throws Exception {
        Path skills = Files.createDirectories(temp.resolve("skills"));
        Path workspaces = Files.createDirectories(temp.resolve("workspaces"));
        AgentSettings settings = new AgentSettings(4, 4, 20_000, 10, false,
                PermissionMode.BALANCED, 0, false, 0, true, false);
        AppConfig config = new AppConfig(temp, skills, workspaces,
                new LlmSettings("sk-test", "http://127.0.0.1:1", "fake", 5, 1024),
                settings, new ServerSettings("127.0.0.1", 0), null, null, true);
        LlmClient llm = request -> {
            ChatMessage last = request.messages().get(request.messages().size() - 1);
            if (last.role() == io.github.jasoncai0.agent.llm.Role.TOOL) {
                return new ChatResult("done", null, List.of(), "stop", Usage.ZERO);
            }
            String text = last.content() == null ? "" : last.content();
            if (text.contains("permission task")) {
                return new ChatResult("", null,
                        List.of(new ToolCall("call-1", "write_file",
                                "{\"path\":\"x.txt\",\"content\":\"x\"}")),
                        "tool_calls", Usage.ZERO);
            }
            return new ChatResult("done", null, List.of(), "stop", Usage.ZERO);
        };
        try (AgentService service = new AgentService(config, llm)) {
            var rootSnapshot = service.start("root task", "team-approval-test");
            assertNotNull(service.await(rootSnapshot.id(), Duration.ofSeconds(5)));
            AgentRun root = service.require(rootSnapshot.id());
            TeamService team = service.team();
            TeamMember member = team.spawn(root, "worker", "permission task");

            var waiting = team.waitFor(root, List.of("worker"), 5_000);
            assertFalse(Boolean.TRUE.equals(waiting.get("timedOut")), String.valueOf(waiting));
            @SuppressWarnings("unchecked")
            List<String> waitingApproval = (List<String>) waiting.get("waitingApproval");
            assertTrue(waitingApproval.contains("worker"), String.valueOf(waiting));
            var pending = team.pendingPermissions(root.id());
            assertEquals(1, pending.size(), String.valueOf(pending));

            @SuppressWarnings("unchecked")
            Map<String, Object> requestView = (Map<String, Object>) pending.get(0);
            assertTrue(team.resolvePermission(root.id(), (String) requestView.get("id"),
                    PermissionDecision.ALLOW, PermissionScope.ONCE, "test approve"));

            var done = team.waitFor(root, List.of("worker"), 10_000);
            assertFalse(Boolean.TRUE.equals(done.get("timedOut")), String.valueOf(done));
            @SuppressWarnings("unchecked")
            List<String> completed = (List<String>) done.get("completed");
            assertTrue(completed.contains("worker"), String.valueOf(done));
            assertEquals("done", member.lastResult());
        }
    }

}
