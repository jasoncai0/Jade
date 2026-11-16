package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionMode;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.question.QuestionBroker;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import io.github.jasoncai0.agent.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopGoalTest {
    @TempDir
    Path temp;

    @Test
    void rejectsFinalAnswerUntilGoalReviewPasses() throws Exception {
        LlmClient llm = new ScriptedLlm();
        Deque<GoalReview> reviews = new ArrayDeque<>();
        reviews.add(GoalReview.reject(List.of("show test evidence"), List.of("answer claims success too early")));
        reviews.add(GoalReview.allow(Usage.ZERO));
        AtomicInteger reviewCalls = new AtomicInteger();
        GoalReviewer reviewer = (task, plan, answer, verification, files) -> {
            reviewCalls.incrementAndGet();
            return reviews.removeFirst();
        };
        AgentSettings settings = new AgentSettings(4, 4, 20_000, 30, true,
                PermissionMode.BYPASS, 0, true);
        AgentLoop loop = new AgentLoop(llm, BuiltInTools.registry(), SkillRegistry.load(temp.resolve("skills")),
                settings, 1024, new Verifier(), new PermissionBroker(), new PermissionSession(),
                new QuestionBroker(), reviewer);
        Path root = temp.resolve("ws");
        Files.createDirectories(root);
        Workspace workspace = Workspace.open(root);
        RunState state = new RunState("run-goal", "Print hello and test it", "ws", 0);
        RunEventLog events = new RunEventLog();
        RunSnapshot snapshot = loop.run("Print hello and test it", workspace, state, events, new RunControl());
        assertEquals("SUCCEEDED", snapshot.status());
        assertEquals("hello with test evidence", snapshot.finalAnswer());
        assertEquals(2, reviewCalls.get());
        assertTrue(events.snapshot().stream().anyMatch(e -> e.type().equals("goal.review")));
        assertTrue(events.snapshot().stream().anyMatch(e -> e.type().equals("goal.rejected")));
    }

    private static final class ScriptedLlm implements LlmClient {
        private int index;
        @Override public ChatResult chat(ChatRequest request) {
            index++;
            if (index == 1) {
                return new ChatResult("Implemented hello.", null, List.of(), "stop", new Usage(10, 2, 12));
            }
            return new ChatResult("hello with test evidence", null, List.of(), "stop", new Usage(10, 2, 12));
        }
    }
}
