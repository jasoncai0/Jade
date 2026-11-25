package io.github.jasoncai0.agent.team;

import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.LlmSettings;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamServiceTest {
    @TempDir
    Path temp;

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
}
