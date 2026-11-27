package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.LlmException;
import io.github.jasoncai0.agent.llm.Role;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.memory.MemoryContext;
import io.github.jasoncai0.agent.memory.MemoryService;
import io.github.jasoncai0.agent.memory.NoopMemoryService;
import io.github.jasoncai0.agent.memory.admission.MemoryAdmissionPipeline;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.question.QuestionBroker;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One run of the agent. Every model turn executes the same ten phases.
 * Streaming throttling, prompt assembly, tool batch execution, and memory
 * bridge logic live in dedicated package-private collaborators.
 */
public final class AgentLoop {
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final AgentSettings settings;
    private final int maxOutputTokens;
    private final Verifier verifier;
    private final PermissionBroker permissionBroker;
    private final PermissionSession permissionSession;
    private final QuestionBroker questionBroker;
    private final GoalReviewer goalReviewer;
    private final ToolBatchExecutor toolExecutor;
    private final MemoryService memoryService;
    private volatile MemoryBridge memoryBridge;
    private final ContextCompactor compactor = new ContextCompactor();
    private final ToolLoopGuard loopGuard = new ToolLoopGuard();
    private volatile LiveDeltaBus liveDeltas;
    private volatile String teamPolicy = "";

    public AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
                     AgentSettings settings, int maxOutputTokens) {
        this(llm, tools, skills, settings, maxOutputTokens, new Verifier());
    }

    AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
              AgentSettings settings, int maxOutputTokens, Verifier verifier) {
        this(llm, tools, skills, settings, maxOutputTokens, verifier,
                new PermissionBroker(), new PermissionSession(), new QuestionBroker(),
                new LlmGoalReviewer(llm));
    }

    AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
              AgentSettings settings, int maxOutputTokens, Verifier verifier,
              PermissionBroker permissionBroker, PermissionSession permissionSession) {
        this(llm, tools, skills, settings, maxOutputTokens, verifier, permissionBroker,
                permissionSession, new QuestionBroker(), new LlmGoalReviewer(llm));
    }

    AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
              AgentSettings settings, int maxOutputTokens, Verifier verifier,
              PermissionBroker permissionBroker, PermissionSession permissionSession,
              QuestionBroker questionBroker) {
        this(llm, tools, skills, settings, maxOutputTokens, verifier, permissionBroker,
                permissionSession, questionBroker, new LlmGoalReviewer(llm));
    }

    AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
              AgentSettings settings, int maxOutputTokens, Verifier verifier,
              PermissionBroker permissionBroker, PermissionSession permissionSession,
              QuestionBroker questionBroker, GoalReviewer goalReviewer) {
        this(llm, tools, skills, settings, maxOutputTokens, verifier, permissionBroker,
                permissionSession, questionBroker, goalReviewer, NoopMemoryService.off());
    }

    AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
              AgentSettings settings, int maxOutputTokens, Verifier verifier,
              PermissionBroker permissionBroker, PermissionSession permissionSession,
              QuestionBroker questionBroker, GoalReviewer goalReviewer, MemoryService memory) {
        this.llm = llm;
        this.tools = tools;
        this.skills = skills;
        this.settings = settings;
        this.maxOutputTokens = maxOutputTokens;
        this.verifier = verifier;
        this.permissionBroker = permissionBroker == null ? new PermissionBroker() : permissionBroker;
        this.permissionSession = permissionSession == null ? new PermissionSession() : permissionSession;
        this.questionBroker = questionBroker == null ? new QuestionBroker() : questionBroker;
        this.goalReviewer = goalReviewer == null ? GoalReviewer.NOOP : goalReviewer;
        this.toolExecutor = new ToolBatchExecutor(tools, settings);
        this.memoryService = memory == null ? NoopMemoryService.off() : memory;
        this.memoryBridge = new MemoryBridge(this.memoryService, llm);
    }

    public AgentLoop withMemoryPipeline(MemoryAdmissionPipeline pipeline) {
        if (pipeline != null) {
            this.memoryBridge = new MemoryBridge(this.memoryService, llm, pipeline);
        }
        return this;
    }

    public AgentLoop withTeamPolicy(String policy) {
        this.teamPolicy = policy == null ? "" : policy;
        return this;
    }

    public AgentLoop withLiveDeltas(LiveDeltaBus bus) {
        this.liveDeltas = bus;
        return this;
    }

    public RunSnapshot run(String task, Workspace workspace, RunState state,
                           RunEventLog log, RunControl control) {
        state.beginTurn();
        List<Skill> selected = skills.select(task, 2);
        List<ChatMessage> messages = new ArrayList<>();
        Log.info("agent.run.start", Map.of(
                "runId", state.id(),
                "workspace", workspace.root().getFileName().toString(),
                "taskChars", task.length()
        ));
        log.emit("run.started", eventMap("snapshot", state.snapshot()));
        String memoryBlock = memoryBridge.recall(task, workspace, state, log);
        String system = SystemPromptBuilder.buildSystemPrompt(
                settings, skills, teamPolicy, task, workspace, selected);
        if (!memoryBlock.isBlank()) {
            system = system + "\n\n" + memoryBlock;
        }
        messages.add(ChatMessage.system(system));
        messages.add(ChatMessage.user(SystemPromptBuilder.buildInitialUserMessage(task, workspace)));
        return runTurn(task, workspace, state, log, control, messages, selected, List.of(task));
    }

    public RunSnapshot continueSession(String message, List<ChatMessage> history,
                                       Workspace workspace, RunState state,
                                       RunEventLog log, RunControl control) {
        state.beginTurn();
        List<Skill> selected = skills.select(message, 2);
        List<ChatMessage> messages = new ArrayList<>(history);
        if (messages.isEmpty() || messages.get(0).role() != Role.SYSTEM) {
            messages.add(0, ChatMessage.system(SystemPromptBuilder.buildSystemPrompt(
                    settings, skills, teamPolicy, state.task(), workspace, selected)));
        }
        String memoryQuery = MemoryBridge.buildRecallQuery(message, messages);
        String memoryBlock = memoryBridge.recall(memoryQuery, workspace, state, log);
        if (!memoryBlock.isBlank()) {
            messages = MemoryContext.inject(messages, memoryBlock);
        }
        messages.add(ChatMessage.user(message));
        Log.info("agent.turn.start", Map.of(
                "runId", state.id(),
                "workspace", workspace.root().getFileName().toString(),
                "messageChars", message.length(),
                "messages", messages.size()
        ));
        log.emit("turn.started", eventMap("message", message, "snapshot", state.snapshot()));
        return runTurn(state.task(), workspace, state, log, control, messages, selected, List.of(message));
    }

    private RunSnapshot runTurn(String task, Workspace workspace, RunState state,
                                RunEventLog log, RunControl control,
                                List<ChatMessage> messages, List<Skill> selected,
                                List<String> initialTurnMessages) {
        ToolContext context = new ToolContext(workspace, settings, skills, state, verifier,
                state.id(), log, questionBroker, control);
        ToolOutputStore spill = new ToolOutputStore(workspace.root());
        context.refreshFiles();
        PermissionManager permissions = new PermissionManager(settings.permissionMode(), permissionSession,
                permissionBroker, state, log, control, workspace);
        loopGuard.reset();
        List<String> turnUserMessages = new ArrayList<>(initialTurnMessages);

        try {
            boolean completed = false;
            int noProgressTurns = 0;
            boolean reflectionRequested = false;
            int goalReviewAttempts = 0;
            int cycle = 1;
            while (true) {
                if (settings.maxSteps() > 0 && cycle > settings.maxSteps()) {
                    break;
                }
                control.checkCancelled();
                if (settings.tokenBudget() > 0 && state.usage().totalTokens() >= settings.tokenBudget()) {
                    String message = "Stopped: session token budget " + settings.tokenBudget()
                            + " was exhausted (" + state.usage().totalTokens() + " tokens). "
                            + "The workspace and transcript are preserved; raise AGENT_TOKEN_BUDGET "
                            + "or send a follow-up to continue.";
                    state.budgetExceeded(message);
                    log.emit("budget.exceeded", eventMap("message", message, "budget", settings.tokenBudget(),
                            "usage", state.usage()));
                    break;
                }
                List<String> filesBefore = List.copyOf(state.files());
                boolean verifiedBefore = context.verified();
                phase(state, log, AgentPhase.INTAKE, cycle,
                        "run " + state.id() + ", task " + task.length() + " chars");
                phase(state, log, AgentPhase.CONTEXT, cycle,
                        messages.size() + " messages, " + compactor.chars(messages) + " chars in context");
                phase(state, log, AgentPhase.SKILLS, cycle,
                        selected.isEmpty() ? "catalog loaded; no skill pre-selected"
                                : "pre-selected " + selected.stream().map(Skill::name).toList());
                phase(state, log, AgentPhase.PLAN, cycle,
                        state.plan().size() + " plan item(s)");
                state.setPhase(AgentPhase.PLAN, cycle);

                phase(state, log, AgentPhase.MODEL, cycle, "asking the model for the next action");
                ChatRequest request = new ChatRequest(messages, tools.specs(), 0.2, maxOutputTokens);
                String requestBody = llm.requestBody(request, true);
                if (requestBody != null && !requestBody.isBlank()) {
                    log.emit("llm.request", eventMap(
                            "body", requestBody,
                            "step", cycle,
                            "messages", request.messages().size(),
                            "tools", request.tools().size(),
                            "chars", requestBody.length()
                    ));
                }
                ChatResult result = requestModel(request, messages, state, log, control, cycle);
                state.addUsage(result.usage());
                log.emit("usage", eventMap("usage", state.usage()));

                boolean hasCalls = !result.toolCalls().isEmpty();
                boolean truncated = "length".equalsIgnoreCase(result.finishReason());
                messages.add(ChatMessage.assistant(
                        result.content(), result.reasoningContent(), result.toolCalls()));
                log.emit("assistant", eventMap(
                        "content", result.content(),
                        "reasoning", result.reasoningContent(),
                        "finishReason", result.finishReason(),
                        "toolCalls", result.toolCalls().stream().map(ToolCall::name).toList()
                ));

                phase(state, log, AgentPhase.TOOLS, cycle,
                        hasCalls ? result.toolCalls().size() + " tool call(s)" : "no tool calls");
                int[] stats = {0, 0};
                if (hasCalls) {
                    String loopReason = null;
                    for (ToolCall call : result.toolCalls()) {
                        loopReason = loopGuard.observe(call);
                        if (loopReason != null) {
                            break;
                        }
                    }
                    if (loopReason != null) {
                        ToolBatchExecutor.rejectToolCalls(result.toolCalls(), messages, loopReason, "loop", log);
                        state.loopDetected(loopReason);
                        log.emit("loop.detected", eventMap("message", loopReason, "usage", state.usage()));
                        break;
                    }
                    if (settings.tokenBudget() > 0
                            && state.usage().totalTokens() >= settings.tokenBudget()) {
                        String message = "Stopped: session token budget " + settings.tokenBudget()
                                + " was exhausted (" + state.usage().totalTokens() + " tokens). "
                                + "The workspace and transcript are preserved; raise AGENT_TOKEN_BUDGET "
                                + "or send a follow-up to continue.";
                        ToolBatchExecutor.rejectToolCalls(result.toolCalls(), messages, message, "budget", log);
                        state.budgetExceeded(message);
                        log.emit("budget.exceeded", eventMap("message", message,
                                "budget", settings.tokenBudget(), "usage", state.usage()));
                        break;
                    }
                    toolExecutor.execute(result.toolCalls(), messages, context, state, log, control, stats, permissions, spill);
                    boolean madeProgress = !state.files().equals(filesBefore)
                            || (!verifiedBefore && context.verified());
                    if (madeProgress) {
                        noProgressTurns = 0;
                        reflectionRequested = false;
                    } else {
                        noProgressTurns++;
                        if (noProgressTurns >= ReflectionPolicy.REFLECT_AFTER && !reflectionRequested) {
                            reflectionRequested = true;
                            String prompt = ReflectionPolicy.prompt(noProgressTurns, result.toolCalls().size());
                            messages.add(ChatMessage.user(prompt));
                            log.emit("reflection.requested", eventMap(
                                    "message", prompt,
                                    "noProgressTurns", noProgressTurns,
                                    "threshold", ReflectionPolicy.REFLECT_AFTER,
                                    "usage", state.usage()));
                        }
                        if (noProgressTurns >= ReflectionPolicy.STALL_AFTER) {
                            String message = ReflectionPolicy.stallMessage(noProgressTurns);
                            state.loopDetected(message);
                            log.emit("loop.detected", eventMap(
                                    "message", message,
                                    "reason", "no_progress",
                                    "noProgressTurns", noProgressTurns,
                                    "usage", state.usage()));
                            break;
                        }
                    }
                    for (ToolCall call : result.toolCalls()) {
                        if ("reflect".equals(call.name())) {
                            RunArtifacts.appendReflection(workspace, state.id(), cycle, call.arguments());
                            log.emit("reflection.recorded", eventMap(
                                    "step", cycle,
                                    "arguments", truncate(call.arguments(), 16_000),
                                    "usage", state.usage()));
                        }
                    }
                }

                phase(state, log, AgentPhase.OBSERVE, cycle,
                        stats[0] + " succeeded, " + stats[1] + " failed; "
                                + context.workspace().listFiles(500, 8).size() + " workspace entries");
                log.emit("plan", eventMap("plan", state.plan()));
                log.emit("workspace", eventMap("files", state.files()));

                phase(state, log, AgentPhase.VERIFY, cycle,
                        hasCalls ? "waiting for the model to stop calling tools" : "final answer: verify artifacts");
                boolean verified = true;
                if (!hasCalls) {
                    if (truncated) {
                        messages.add(ChatMessage.user(
                                "Your response was cut off by the max_tokens limit. Continue the task "
                                        + "and finish with a concise final answer."));
                        verified = false;
                    } else {
                        verified = verifyNow(context, state, log, cycle, permissions);
                        if (!verified) {
                            messages.add(ChatMessage.user(verificationFeedback(state.verification())));
                        }
                    }
                }

                phase(state, log, AgentPhase.COMPACT, cycle, "budget "
                        + settings.contextCharBudget() + " chars");
                ContextCompactor.Result compaction = compactor.compact(
                        messages, settings.contextCharBudget(), settings.contextTokenBudget(),
                        new LlmContextSummarizer(llm));
                if (compaction.compacted()) {
                    messages.clear();
                    messages.addAll(compaction.messages());
                    if (compaction.summaryUsage().totalTokens() > 0) {
                        state.addUsage(compaction.summaryUsage());
                    }
                    log.emit("compaction", eventMap(
                            "removed", compaction.removed(),
                            "microCompacted", compaction.microCompacted(),
                            "truncated", compaction.truncated(),
                            "beforeChars", compaction.beforeChars(),
                            "afterChars", compaction.afterChars(),
                            "beforeTokens", compaction.beforeTokens(),
                            "afterTokens", compaction.afterTokens(),
                            "summarized", compaction.summarized(),
                            "summaryTokens", compaction.summaryUsage().totalTokens()
                    ));
                }

                context.refreshFiles();
                String transcript = persistArtifacts(workspace, state, messages, log);
                state.setTranscriptPath(transcript);
                phase(state, log, AgentPhase.CHECKPOINT, cycle,
                        state.files().size() + " workspace entries; transcript " + (transcript == null ? "unavailable" : transcript));

                if (!hasCalls && verified) {
                    String answer = result.content() == null || result.content().isBlank()
                            ? "Task completed. " + state.verification()
                            : result.content();
                    if (settings.goalMode()) {
                        GoalReview review = goalReviewer.review(task, state.plan(), answer,
                                state.verification(), state.files());
                        if (review.usage().totalTokens() > 0) {
                            state.addUsage(review.usage());
                        }
                        log.emit("goal.review", eventMap(
                                "aligned", review.aligned(),
                                "missing", review.missing(),
                                "issues", review.issues(),
                                "answer", answer,
                                "verification", state.verification(),
                                "usage", review.usage()
                        ));
                        if (!review.aligned()) {
                            goalReviewAttempts++;
                            String feedback = goalFeedback(review);
                            if (goalReviewAttempts >= 3) {
                                String message = "Goal review rejected the result after 3 attempts: " + feedback;
                                state.fail(message);
                                log.emit("run.failed", eventMap("error", message, "goalReview", true));
                                break;
                            }
                            messages.add(ChatMessage.user("GOAL REVIEW FAILED. " + feedback
                                    + " Continue working and finish with a concise answer that addresses the original task."));
                            log.emit("goal.rejected", eventMap("attempt", goalReviewAttempts,
                                    "missing", review.missing(), "issues", review.issues()));
                            cycle++;
                            continue;
                        }
                    }
                    memoryBridge.maybeWrite(task, turnUserMessages, answer, workspace, state, log);
                    state.succeed(answer);
                    log.emit("run.succeeded", eventMap("answer", answer, "usage", state.usage()));
                    completed = true;
                    break;
                }
                cycle++;
            }

            if (!completed && state.status().equals(RunStatus.RUNNING.name())) {
                if (settings.autoVerify() && (context.dirty() || context.touched())) {
                    verifyNow(context, state, log, settings.maxSteps(), permissions);
                }
                String message = "Stopped after " + settings.maxSteps()
                        + " model turns (AGENT_MAX_STEPS). The workspace and transcript are preserved; "
                        + "increase the limit or send a follow-up task to continue.";
                state.maxSteps(message);
                log.emit("run.max_steps", eventMap("message", message, "usage", state.usage()));
            }
        } catch (RunControl.RunCancelledException e) {
            state.cancel();
            log.emit("run.cancelled", eventMap("message", "Run cancelled by the user"));
            Log.info("agent.run.cancelled", Map.of("runId", state.id()));
        } catch (LlmException e) {
            String message = e.getMessage() == null ? "DeepSeek request failed" : e.getMessage();
            state.fail(message);
            log.emit("run.failed", eventMap("error", message));
            Log.warn("agent.run.llm_failed", Map.of("runId", state.id(), "error", message));
        } catch (RuntimeException e) {
            String message = "Internal agent error: " + e.getMessage();
            state.fail(message);
            log.emit("run.failed", eventMap("error", message));
            Log.error("agent.run.failed", e, Map.of("runId", state.id()));
        } finally {
            try {
                context.refreshFiles();
                String transcript = persistArtifacts(workspace, state, messages, log);
                state.setTranscriptPath(transcript);
            } catch (RuntimeException e) {
                Log.warn("agent.artifacts.failed", Map.of("runId", state.id(), "error", String.valueOf(e.getMessage())));
            }
            RunSnapshot snapshot = state.snapshot();
            log.emit("run.finished", eventMap("snapshot", snapshot));
            try {
                persistArtifacts(workspace, state, messages, log);
            } catch (RuntimeException e) {
                Log.warn("agent.artifacts.final_failed", Map.of(
                        "runId", state.id(), "error", String.valueOf(e.getMessage())));
            }
            Log.info("agent.run.finished", Map.of(
                    "runId", state.id(),
                    "status", snapshot.status(),
                    "steps", snapshot.step(),
                    "usage", snapshot.usage().totalTokens()
            ));
        }
        return state.snapshot();
    }

    private ChatResult requestModel(ChatRequest request, List<ChatMessage> messages,
                                    RunState state, RunEventLog log, RunControl control,
                                    int cycle) {
        boolean recovered = false;
        while (true) {
            try {
                return DeltaThrottle.stream(llm, request, liveDeltas);
            } catch (LlmException e) {
                if (control.isCancelled()) {
                    throw new RunControl.RunCancelledException();
                }
                if (!settings.overflowRecovery() || !e.isContextOverflow()) {
                    throw e;
                }
                if (recovered) {
                    throw new LlmException(e.getMessage()
                            + " Context still exceeds the model window after one compaction; "
                            + "reduce the task or start a new session.",
                            e.statusCode(), e.code(), e);
                }
                recovered = true;
                log.emit("context.overflow", eventMap(
                        "step", cycle,
                        "message", e.getMessage(),
                        "code", e.code(),
                        "beforeChars", compactor.chars(messages),
                        "beforeTokens", compactor.tokens(messages)
                ));
                ContextCompactor.Result recovery = compactor.compactForOverflow(
                        messages, new LlmContextSummarizer(llm));
                messages.clear();
                messages.addAll(recovery.messages());
                if (recovery.summaryUsage().totalTokens() > 0) {
                    state.addUsage(recovery.summaryUsage());
                }
                log.emit("context.compacted", eventMap(
                        "removed", recovery.removed(),
                        "microCompacted", recovery.microCompacted(),
                        "truncated", recovery.truncated(),
                        "beforeTokens", recovery.beforeTokens(),
                        "afterTokens", recovery.afterTokens(),
                        "summaryTokens", recovery.summaryUsage().totalTokens(),
                        "summarized", recovery.summarized()
                ));
                request = new ChatRequest(messages, tools.specs(), 0.2, maxOutputTokens);
                String body = llm.requestBody(request, true);
                if (body != null && !body.isBlank()) {
                    log.emit("llm.request", eventMap(
                            "body", body,
                            "step", cycle,
                            "messages", request.messages().size(),
                            "tools", request.tools().size(),
                            "chars", body.length(),
                            "recovery", true
                    ));
                }
            }
        }
    }

    private boolean verifyNow(ToolContext context, RunState state, RunEventLog log,
                              int cycle, PermissionManager permissions) {
        if (!settings.autoVerify()) {
            String message = "Automatic verification is disabled (AGENT_AUTO_VERIFY=false).";
            state.setVerification(message);
            log.emit("verification", eventMap("ok", true, "skipped", true, "summary", message,
                    "command", "", "output", "", "step", cycle));
            return true;
        }
        if (context.verified() && !context.dirty()) {
            String message = "Already verified by an earlier compile_code or run_code call.";
            state.setVerification(message);
            log.emit("verification", eventMap("ok", true, "skipped", true, "summary", message,
                    "command", "", "output", "", "step", cycle));
            return true;
        }
        if (!context.dirty() && !context.touched()) {
            String message = "No source changes to verify.";
            state.setVerification(message);
            log.emit("verification", eventMap("ok", true, "skipped", true, "summary", message,
                    "command", "", "output", "", "step", cycle));
            return true;
        }
        if (permissions != null && hasMavenBuild(context.workspace())) {
            PermissionResult approval = permissions.authorize("compile_code", Map.of("_buildExecutesCode", true));
            if (!approval.allowed()) {
                String summary = "Automatic Maven verification blocked: " + approval.reason();
                state.setVerification(summary);
                log.emit("verification", eventMap("ok", false, "skipped", false, "summary", summary,
                        "command", "mvn compile", "output", summary, "step", cycle));
                return false;
            }
        }
        Verifier.VerificationResult result = verifier.verify(
                context.workspace(), settings.commandTimeoutSeconds());
        state.setVerification(result.summary());
        if (result.ok()) {
            context.markVerified();
        }
        Map<String, Object> data = new LinkedHashMap<>(result.toEventData());
        data.put("step", cycle);
        log.emit("verification", data);
        return result.ok();
    }

    private static boolean hasMavenBuild(Workspace workspace) {
        Path root = workspace.root();
        return Files.isRegularFile(root.resolve("pom.xml"))
                || Files.isExecutable(root.resolve("mvnw"))
                || Files.isRegularFile(root.resolve("mvnw.cmd"));
    }

    private static String verificationFeedback(String summary) {
        return "Automatic verification failed before the task can finish: " + summary + "\n"
                + "Inspect the exact compiler/interpreter output, fix the source with edit_file or write_file, "
                + "then call compile_code or run_code again. Do not claim success until the real toolchain passes.";
    }

    private static String goalFeedback(GoalReview review) {
        StringBuilder out = new StringBuilder();
        if (!review.missing().isEmpty()) {
            out.append("Missing requirements: ").append(String.join("; ", review.missing())).append(". ");
        }
        if (!review.issues().isEmpty()) {
            out.append("Issues: ").append(String.join("; ", review.issues())).append(". ");
        }
        return out.length() == 0 ? "The final answer does not satisfy the original task." : out.toString().trim();
    }

    private static void phase(RunState state, RunEventLog log, AgentPhase phase, int cycle, String detail) {
        state.setPhase(phase, cycle);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase.id());
        data.put("label", phase.label());
        data.put("description", phase.description());
        data.put("detail", detail);
        data.put("step", cycle);
        data.put("maxSteps", state.maxSteps());
        log.emit("phase", data);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "\n... [truncated]";
    }

    private static Map<String, Object> eventMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] != null && keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }

    private static String persistArtifacts(Workspace workspace, RunState state,
                                           List<ChatMessage> messages, RunEventLog log) {
        String dir = state.isSubagent() ? ".agent/subagents/" + state.id() : ".agent";
        return RunArtifacts.persist(workspace, state.snapshot(), messages, log, dir);
    }
}
