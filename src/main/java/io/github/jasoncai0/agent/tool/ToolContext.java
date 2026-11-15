package io.github.jasoncai0.agent.tool;

import io.github.jasoncai0.agent.agent.PlanItem;
import io.github.jasoncai0.agent.agent.RunState;
import io.github.jasoncai0.agent.agent.Verifier;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ToolContext {
    private final Workspace workspace;
    private final AgentSettings settings;
    private final SkillRegistry skills;
    private final RunState state;
    private final Verifier verifier;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicBoolean touched = new AtomicBoolean();
    private final AtomicBoolean verified = new AtomicBoolean();
    private final Set<String> loadedSkills = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean networkAllowed = new AtomicBoolean();

    public ToolContext(Workspace workspace, AgentSettings settings, SkillRegistry skills,
                       RunState state, Verifier verifier) {
        this.workspace = workspace;
        this.settings = settings;
        this.skills = skills;
        this.state = state;
        this.verifier = verifier;
    }

    public Workspace workspace() {
        return workspace;
    }

    public AgentSettings settings() {
        return settings;
    }

    public SkillRegistry skills() {
        return skills;
    }

    public RunState state() {
        return state;
    }

    public Verifier verifier() {
        return verifier;
    }

    public void setNetworkAllowed(boolean allowed) {
        networkAllowed.set(allowed);
    }

    public boolean networkAllowed() {
        return networkAllowed.get();
    }

    public void markDirty() {
        dirty.set(true);
        touched.set(true);
        verified.set(false);
    }

    public void markTouched() {
        touched.set(true);
        verified.set(false);
    }

    public void markVerified() {
        dirty.set(false);
        verified.set(true);
    }

    public boolean dirty() {
        return dirty.get();
    }

    public boolean touched() {
        return touched.get();
    }

    public boolean verified() {
        return verified.get();
    }

    public void updatePlan(List<PlanItem> plan) {
        state.setPlan(plan);
    }

    public void skillLoaded(String name) {
        loadedSkills.add(name);
    }

    public boolean skillWasLoaded(String name) {
        return loadedSkills.contains(name);
    }

    public void refreshFiles() {
        state.setFiles(workspace.listFiles(500, 8));
    }
}
