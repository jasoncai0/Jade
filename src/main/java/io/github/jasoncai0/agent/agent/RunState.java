package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RunState {
    private final String id;
    private final String task;
    private final String workspace;
    private final int maxSteps;
    private final long startedAt = System.currentTimeMillis();
    private final List<PlanItem> plan = new CopyOnWriteArrayList<>();
    private final List<String> files = new CopyOnWriteArrayList<>();
    private volatile String status = RunStatus.QUEUED.name();
    private volatile String phase = AgentPhase.INTAKE.id();
    private volatile String phaseLabel = AgentPhase.INTAKE.label();
    private volatile int step;
    private volatile Usage usage = Usage.ZERO;
    private volatile String finalAnswer;
    private volatile String error;
    private volatile String verification;
    private volatile long finishedAt;
    private volatile String transcriptPath;

    public RunState(String id, String task, String workspace, int maxSteps) {
        this.id = id;
        this.task = task;
        this.workspace = workspace;
        this.maxSteps = maxSteps;
        this.plan.addAll(defaultPlan());
    }

    public String id() {
        return id;
    }

    public String task() {
        return task;
    }

    public String workspace() {
        return workspace;
    }

    public int maxSteps() {
        return maxSteps;
    }

    public String status() {
        return status;
    }

    public String phase() {
        return phase;
    }

    public String phaseLabel() {
        return phaseLabel;
    }

    public int step() {
        return step;
    }

    public Usage usage() {
        return usage;
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public String error() {
        return error;
    }

    public String verification() {
        return verification;
    }

    public List<PlanItem> plan() {
        return List.copyOf(plan);
    }

    public List<String> files() {
        return List.copyOf(files);
    }

    public synchronized void markRunning() {
        status = RunStatus.RUNNING.name();
    }

    public synchronized void setPhase(AgentPhase next, int stepNumber) {
        this.phase = next.id();
        this.phaseLabel = next.label();
        this.step = stepNumber;
    }

    public synchronized void setPlan(List<PlanItem> next) {
        plan.clear();
        plan.addAll(next);
    }

    public synchronized void setFiles(List<Workspace.FileEntry> entries) {
        files.clear();
        entries.stream().limit(500).forEach(entry -> files.add(entry.path()));
    }

    public synchronized void addUsage(Usage delta) {
        usage = usage.plus(delta);
    }

    public synchronized void setVerification(String summary) {
        verification = summary;
    }

    public synchronized void succeed(String answer) {
        status = RunStatus.SUCCEEDED.name();
        finalAnswer = answer;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void fail(String message) {
        status = RunStatus.FAILED.name();
        error = message;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void cancel() {
        status = RunStatus.CANCELLED.name();
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void maxSteps(String message) {
        status = RunStatus.MAX_STEPS.name();
        finalAnswer = message;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void setTranscriptPath(String path) {
        transcriptPath = path;
    }

    public synchronized RunSnapshot snapshot() {
        return new RunSnapshot(
                id,
                task,
                workspace,
                status,
                phase,
                phaseLabel,
                step,
                maxSteps,
                List.copyOf(plan),
                List.copyOf(files),
                usage,
                finalAnswer,
                error,
                verification,
                startedAt,
                finishedAt,
                transcriptPath
        );
    }

    public static List<PlanItem> defaultPlan() {
        List<PlanItem> items = new ArrayList<>();
        items.add(new PlanItem("Inspect the workspace and task", "in_progress"));
        items.add(new PlanItem("Implement the change with tools", "pending"));
        items.add(new PlanItem("Compile, test, and run the artifact", "pending"));
        items.add(new PlanItem("Report the verified result", "pending"));
        return items;
    }
}
