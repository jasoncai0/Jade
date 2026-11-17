package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopSkillPromptTest {
    @TempDir
    Path temp;

    @Test
    void largeSkillIsCataloguedButNotPreloaded() throws Exception {
        String system = runAndCaptureSystemPrompt(true);
        assertTrue(system.contains("big-skill"), system);
        assertTrue(system.contains("call load_skill"), system);
        assertTrue(system.contains("full instructions are "), system);
        assertFalse(system.contains("TAIL_MARKER"), system);
    }

    @Test
    void catalogOnlyModeDoesNotPreloadTheBody() throws Exception {
        String system = runAndCaptureSystemPrompt(false);
        assertTrue(system.contains("catalog-only mode"), system);
        assertTrue(system.contains("big-skill"), system);
        assertFalse(system.contains("TAIL_MARKER"), system);
    }

    private String runAndCaptureSystemPrompt(boolean preloadSkills) throws Exception {
        Path root = temp.resolve("ws-" + preloadSkills);
        Files.createDirectories(root.resolve(".agent-build/classes"));
        Workspace workspace = Workspace.open(root);
        Path skillsDir = temp.resolve("skills-" + preloadSkills);
        Path skillDir = Files.createDirectories(skillsDir.resolve("big"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: big-skill
                description: A deliberately long skill.
                triggers: [big]
                ---
                FIRST
                %s
                TAIL_MARKER
                """.formatted("x".repeat(5_000)));

        AgentSettings settings = new AgentSettings(4, 4, 20_000, 30, true,
                PermissionMode.BYPASS, 0, false, 0, true, preloadSkills);
        ScriptedLlm llm = new ScriptedLlm();
        llm.script.add(new ChatResult("done", null, List.of(), "stop", new Usage(1, 1, 2)));
        AgentLoop loop = new AgentLoop(llm, new ToolRegistry(), SkillRegistry.load(skillsDir),
                settings, 1024);
        RunEventLog events = new RunEventLog();
        loop.run("big task", workspace, new RunState("run-skill", "big task", "ws", 4),
                events, new RunControl());
        return llm.requests.get(0).messages().get(0).content();
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<ChatResult> script = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResult chat(ChatRequest request) {
            requests.add(request);
            ChatResult next = script.pollFirst();
            if (next == null) {
                throw new IllegalStateException("No scripted response");
            }
            return next;
        }
    }
}
