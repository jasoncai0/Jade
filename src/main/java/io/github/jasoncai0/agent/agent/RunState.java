package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.llm.Usage;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.question.QuestionRequest;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RunState {
    private final String id;
    private final String task;
    private final String workspace;
    private final int maxSteps;
    private final long startedAt;
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
    private volatile PermissionRequest pendingPermission;
    private volatile QuestionRequest pendingQuestion;
    private volatile String parentRunId;
    private volatile Long forkSeq;
    private volatile boolean subagent;
    private volatile Usage subagentUsage = Usage.ZERO;

    public RunState(String id, String task, String workspace, int maxSteps) {
        this(id, task, workspace, maxSteps, System.currentTimeMillis());
    }

    RunState(String id, String task, String workspace, int maxSteps, long startedAt) {
        this.id = id;
        this.task = task;
        this.workspace = workspace;
        this.maxSteps = maxSteps;
        this.startedAt = startedAt > 0 ? startedAt : System.currentTimeMillis();
        this.plan.addAll(defaultPlan());
    }

    /**
     * Rebuilds mutable run state from a persisted snapshot. A snapshot that was
     * still RUNNING/QUEUED is surfaced as FAILED because its executor died with
     * the previous process.
     */
    static RunState restore(RunSnapshot snapshot) {
        RunState state = new RunState(snapshot.id(), snapshot.task(), snapshot.workspace(),
                snapshot.maxSteps(), snapshot.startedAt());
        state.plan.clear();
        if (snapshot.plan() != null) {
            state.plan.addAll(snapshot.plan());
        }
        state.files.clear();
        if (snapshot.files() != null) {
            state.files.addAll(snapshot.files());
        }
        state.status = snapshot.status();
        state.phase = snapshot.phase() == null ? AgentPhase.CHECKPOINT.id() : snapshot.phase();
        state.phaseLabel = snapshot.phaseLabel() == null ? AgentPhase.CHECKPOINT.label() : snapshot.phaseLabel();
        state.step = snapshot.step();
        state.usage = snapshot.usage() == null ? Usage.ZERO : snapshot.usage();
        state.finalAnswer = snapshot.finalAnswer();
        state.error = snapshot.error();
        state.verification = snapshot.verification();
        state.finishedAt = snapshot.finishedAt();
        state.transcriptPath = snapshot.transcriptPath();
        state.parentRunId = snapshot.parentRunId();
        state.forkSeq = snapshot.forkSeq();
        if (!snapshot.terminal()) {
            state.status = RunStatus.FAILED.name();
            state.error = "Run was interrupted by a server restart";
            state.finishedAt = System.currentTimeMillis();
        }
        return state;
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

    /**
     * Starts a new user turn in the same session. Conversation history and the
     * workspace survive; the per-turn phase/step/verification state resets.
     */
    public synchronized void beginTurn() {
        status = RunStatus.RUNNING.name();
        phase = AgentPhase.INTAKE.id();
        phaseLabel = AgentPhase.INTAKE.label();
        step = 0;
        finalAnswer = null;
        error = null;
        verification = null;
        finishedAt = 0;
        plan.clear();
        plan.addAll(defaultPlan());
        pendingPermission = null;
        pendingQuestion = null;
    }

    public synchronized void awaitPermission(PermissionRequest request) {
        pendingPermission = request;
        status = RunStatus.WAITING_APPROVAL.name();
    }

    public synchronized void resolvePermission() {
        pendingPermission = null;
        if (RunStatus.WAITING_APPROVAL.name().equals(status)) {
            status = RunStatus.RUNNING.name();
        }
    }

    public PermissionRequest pendingPermission() {
        return pendingPermission;
    }

    public synchronized void awaitQuestion(QuestionRequest request) {
        pendingQuestion = request;
        status = RunStatus.WAITING_INPUT.name();
    }

    public synchronized void resolveQuestion() {
        pendingQuestion = null;
        if (RunStatus.WAITING_INPUT.name().equals(status)) {
            status = RunStatus.RUNNING.name();
        }
    }

    public QuestionRequest pendingQuestion() {
        return pendingQuestion;
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

    /** Child work billed separately so the parent can show delegation cost. */
    public synchronized void addSubagentUsage(Usage delta) {
        subagentUsage = subagentUsage.plus(delta);
    }

    public Usage subagentUsage() {
        return subagentUsage;
    }

    void markSubagent(String parentId) {
        this.subagent = true;
        this.parentRunId = parentId;
    }

    public boolean isSubagent() {
        return subagent;
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

    public synchronized void loopDetected(String message) {
        status = RunStatus.LOOP_DETECTED.name();
        phase = AgentPhase.CHECKPOINT.id();
        phaseLabel = AgentPhase.CHECKPOINT.label();
        error = message;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void budgetExceeded(String message) {
        status = RunStatus.BUDGET_EXCEEDED.name();
        phase = AgentPhase.CHECKPOINT.id();
        phaseLabel = AgentPhase.CHECKPOINT.label();
        error = message;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void maxSteps(String message) {
        status = RunStatus.MAX_STEPS.name();
        finalAnswer = message;
        finishedAt = System.currentTimeMillis();
    }

    public String parentRunId() {
        return parentRunId;
    }

    public Long forkSeq() {
        return forkSeq;
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
                transcriptPath,
                parentRunId,
                forkSeq
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
