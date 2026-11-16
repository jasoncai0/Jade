package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.ToolCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolLoopGuardTest {
    @Test
    void detectsSameCommandShapeWhenOnlyNumbersChange() throws Exception {
        ToolLoopGuard guard = new ToolLoopGuard();
        String reason = null;
        for (int i = 0; i < 8; i++) {
            String command = "javap -p -c AssetService.class | sed -n '" + (i * 20) + "," + (i * 20 + 20) + "p'";
            reason = guard.observe(new ToolCall("call-" + i, "run_command",
                    "{\"command\":\"" + command + "\"}"));
        }
        assertNotNull(reason);
        assertTrue(reason.contains("same command shape"), reason);
    }

    @Test
    void doesNotTriggerOnDifferentCommandShapes() throws Exception {
        ToolLoopGuard guard = new ToolLoopGuard();
        String reason = null;
        String[] words = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"};
        for (int i = 0; i < words.length; i++) {
            reason = guard.observe(new ToolCall("call-" + i, "run_command",
                    "{\"command\":\"echo " + words[i] + "\"}"));
            assertNull(reason);
        }
    }
}
