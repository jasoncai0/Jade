package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.ResponseFormat;
import io.github.jasoncai0.agent.llm.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmGoalReviewerTest {
    @Test
    void parsesRejectedReviewAndUsesJsonMode() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        LlmClient llm = request -> {
            captured.set(request);
            return new ChatResult("""
                    {"aligned":false,"missing":["run tests"],"issues":["no evidence"]}
                    """, null, List.of(), "stop", new Usage(11, 7, 18));
        };
        GoalReview review = new LlmGoalReviewer(llm).review(
                "build and test the CLI",
                List.of(new PlanItem("write code", "completed")),
                "done", "javac passed", List.of("src/Main.java"));
        assertFalse(review.aligned());
        assertEquals(List.of("run tests"), review.missing());
        assertEquals(List.of("no evidence"), review.issues());
        assertEquals(18, review.usage().totalTokens());
        assertEquals(ResponseFormat.JSON_OBJECT, captured.get().responseFormat());
        assertTrue(captured.get().messages().get(1).content().contains("build and test the CLI"));
    }

    @Test
    void acceptsAlignedReview() {
        LlmClient llm = request -> new ChatResult(
                "{\"aligned\":true,\"missing\":[],\"issues\":[]}", null, List.of(), "stop",
                new Usage(10, 4, 14));
        GoalReview review = new LlmGoalReviewer(llm).review(
                "print hello", List.of(), "hello printed", "java exit 0", List.of());
        assertTrue(review.aligned());
        assertTrue(review.missing().isEmpty());
    }
}
