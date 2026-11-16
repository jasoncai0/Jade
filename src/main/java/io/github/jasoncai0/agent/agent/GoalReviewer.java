package io.github.jasoncai0.agent.agent;

import java.util.List;

/** Independent check that the final answer still satisfies the original task. */
@FunctionalInterface
public interface GoalReviewer {
    GoalReviewer NOOP = (task, plan, answer, verification, files) -> GoalReview.allow(
            io.github.jasoncai0.agent.llm.Usage.ZERO);

    GoalReview review(String task, List<PlanItem> plan, String answer,
                      String verification, List<String> files);
}
