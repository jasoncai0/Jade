package io.github.jasoncai0.agent.agent;

public enum AgentPhase {
    INTAKE("intake", "Intake", "Validate task and prepare the run"),
    CONTEXT("context", "Context", "Load workspace state and conversation budget"),
    SKILLS("skills", "Skills", "Discover and select relevant skills"),
    PLAN("plan", "Plan", "Maintain the execution plan"),
    MODEL("model", "Model", "Ask DeepSeek for the next action"),
    TOOLS("tools", "Tools", "Execute requested tools"),
    OBSERVE("observe", "Observe", "Turn tool output into observations"),
    VERIFY("verify", "Verify", "Compile and check the actual artifacts"),
    COMPACT("compact", "Compact", "Keep the context inside its budget"),
    CHECKPOINT("checkpoint", "Checkpoint", "Persist transcript and workspace state");

    private final String id;
    private final String label;
    private final String description;

    AgentPhase(String id, String label, String description) {
        this.id = id;
        this.label = label;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
