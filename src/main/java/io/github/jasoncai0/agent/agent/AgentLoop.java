package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.llm.ChatMessage;
import io.github.jasoncai0.agent.llm.ChatRequest;
import io.github.jasoncai0.agent.llm.ChatResult;
import io.github.jasoncai0.agent.llm.LlmClient;
import io.github.jasoncai0.agent.llm.LlmException;
import io.github.jasoncai0.agent.llm.ToolCall;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.question.QuestionBroker;
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.Workspace;
import io.github.jasoncai0.agent.workspace.WorkspaceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One run of the agent. Every model turn executes the same ten phases. Turn
 * count is unlimited by default; a positive AgentSettings.maxSteps is an
 * optional cap, and a token budget plus repeated-call guard bound long runs.
 */
public final class AgentLoop {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TOOL_OUTPUT_CHARS = 30_000;

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
    private final ContextCompactor compactor = new ContextCompactor();
    private final ToolLoopGuard loopGuard = new ToolLoopGuard();
    /** Live delta sink; null means nobody is watching, so deltas are dropped. */
    private volatile LiveDeltaBus liveDeltas;

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
    }

    /** Routes streaming deltas to a live bus (browser SSE). Optional. */
    public AgentLoop withLiveDeltas(LiveDeltaBus bus) {
        this.liveDeltas = bus;
        return this;
    }

    /**
     * Streams one model request. Deltas are advisory: on failure they are
     * discarded, so a half-streamed answer never looks complete.
     */
    private ChatResult streamModel(ChatRequest request) {
        LiveDeltaBus bus = liveDeltas;
        if (bus == null) {
            return llm.stream(request, LlmClient.StreamListener.noop());
        }
        DeltaThrottle throttle = new DeltaThrottle(bus);
        ChatResult result;
        try {
            result = llm.stream(request, throttle);
        } catch (RuntimeException e) {
            throttle.discard();
            bus.finish();
            throw e;
        }
        throttle.flush();
        bus.finish();
        return result;
    }

    /**
     * Coalesces token deltas into chunks before they hit the SSE connection:
     * at most one frame per {@value #DELTA_INTERVAL_MS} ms or per
     * {@value #DELTA_CHUNK_CHARS} characters, whichever comes first.
     */
    private static final class DeltaThrottle implements LlmClient.StreamListener {
        private static final long DELTA_INTERVAL_MS = 50;
        private static final int DELTA_CHUNK_CHARS = 512;

        private final LiveDeltaBus bus;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private long lastFlush = System.currentTimeMillis();

        private DeltaThrottle(LiveDeltaBus bus) {
            this.bus = bus;
        }

        @Override
        public void onText(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            synchronized (this) {
                text.append(delta);
            }
            maybeFlush();
        }

        @Override
        public void onReasoning(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            synchronized (this) {
                reasoning.append(delta);
            }
            maybeFlush();
        }

        @Override
        public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
            // Tool arguments are not rendered live; they land in the durable log.
        }

        @Override
        public void onUsage(io.github.jasoncai0.agent.llm.Usage usage) {
            // Usage lands in the durable log with the finished assistant message.
        }

        private void maybeFlush() {
            long now = System.currentTimeMillis();
            synchronized (this) {
                int pending = text.length() + reasoning.length();
                if (pending == 0) {
                    return;
                }
                if (pending >= DELTA_CHUNK_CHARS || now - lastFlush >= DELTA_INTERVAL_MS) {
                    flushLocked();
                }
            }
        }

        void flush() {
            synchronized (this) {
                flushLocked();
            }
        }

        void discard() {
            synchronized (this) {
                text.setLength(0);
                reasoning.setLength(0);
            }
        }

        private void flushLocked() {
            if (text.length() == 0 && reasoning.length() == 0) {
                return;
            }
            bus.publish(text.length() == 0 ? "" : text.toString(),
                    reasoning.length() == 0 ? null : reasoning.toString());
            text.setLength(0);
            reasoning.setLength(0);
            lastFlush = System.currentTimeMillis();
        }
    }

    public RunSnapshot run(String task, Workspace workspace, RunState state,
                           RunEventLog log, RunControl control) {
        state.beginTurn();
        List<Skill> selected = skills.select(task, 2);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(task, workspace, selected)));
        messages.add(ChatMessage.user(buildInitialUserMessage(task, workspace)));
        Log.info("agent.run.start", Map.of(
                "runId", state.id(),
                "workspace", workspace.root().getFileName().toString(),
                "taskChars", task.length()
        ));
        log.emit("run.started", eventMap("snapshot", state.snapshot()));
        return runTurn(task, workspace, state, log, control, messages, selected, false);
    }

    /**
     * Continues an existing session with a new user message. The previous
     * conversation is kept, the workspace is reused, and the same event log
     * gets new events with monotonically increasing sequence numbers.
     */
    public RunSnapshot continueSession(String message, List<ChatMessage> history,
                                      Workspace workspace, RunState state,
                                      RunEventLog log, RunControl control) {
        state.beginTurn();
        List<Skill> selected = skills.select(message, 2);
        List<ChatMessage> messages = new ArrayList<>(history);
        if (messages.isEmpty() || messages.get(0).role() != io.github.jasoncai0.agent.llm.Role.SYSTEM) {
            messages.add(0, ChatMessage.system(buildSystemPrompt(state.task(), workspace, selected)));
        }
        messages.add(ChatMessage.user(message));
        Log.info("agent.turn.start", Map.of(
                "runId", state.id(),
                "workspace", workspace.root().getFileName().toString(),
                "messageChars", message.length(),
                "messages", messages.size()
        ));
        log.emit("turn.started", eventMap("message", message, "snapshot", state.snapshot()));
        return runTurn(state.task(), workspace, state, log, control, messages, selected, true);
    }

    private RunSnapshot runTurn(String task, Workspace workspace, RunState state,
                                RunEventLog log, RunControl control,
                                List<ChatMessage> messages, List<Skill> selected,
                                boolean continuation) {
        ToolContext context = new ToolContext(workspace, settings, skills, state, verifier,
                state.id(), log, questionBroker, control);
        ToolOutputStore spill = new ToolOutputStore(workspace.root());
        context.refreshFiles();
        PermissionManager permissions = new PermissionManager(settings.permissionMode(), permissionSession,
                permissionBroker, state, log, control, workspace);
        loopGuard.reset();

        try {
            boolean completed = false;
            int totalToolCalls = 0;
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

                phase(state, log, AgentPhase.MODEL, cycle,
                        "asking the model for the next action");
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
                ChatMessage assistant = ChatMessage.assistant(
                        result.content(), result.reasoningContent(), result.toolCalls());
                messages.add(assistant);
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
                        rejectToolCalls(result.toolCalls(), messages, loopReason, "loop", state, log);
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
                        rejectToolCalls(result.toolCalls(), messages, message, "budget", state, log);
                        state.budgetExceeded(message);
                        log.emit("budget.exceeded", eventMap("message", message,
                                "budget", settings.tokenBudget(), "usage", state.usage()));
                        break;
                    }
                    totalToolCalls += result.toolCalls().size();
                    executeTools(result.toolCalls(), messages, context, state, log, control, stats, permissions, spill);
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
                        verified = verifyNow(context, state, log, cycle);
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
                    state.succeed(answer);
                    log.emit("run.succeeded", eventMap("answer", answer, "usage", state.usage()));
                    completed = true;
                    break;
                }
                cycle++;
            }

            if (!completed && state.status().equals(RunStatus.RUNNING.name())) {
                if (settings.autoVerify() && (context.dirty() || context.touched())) {
                    verifyNow(context, state, log, settings.maxSteps());
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
                // Persist once more so a restarted process can replay the final
                // run.finished event, not just the earlier checkpoint.
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

    private void rejectToolCalls(List<ToolCall> calls, List<ChatMessage> messages, String reason,
                                 String guard, RunState state, RunEventLog log) {
        for (ToolCall call : calls) {
            String output = "Blocked by " + guard + " guard: " + reason;
            messages.add(ChatMessage.tool(call.id(), output));
            log.emit("tool.start", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "arguments", call.arguments()
            ));
            log.emit("tool.end", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "ok", false,
                    "output", output,
                    "durationMs", 0L,
                    "data", Map.of("guard", guard)
            ));
        }
    }

    private void executeTools(List<ToolCall> calls, List<ChatMessage> messages,
                              ToolContext context, RunState state, RunEventLog log,
                              RunControl control, int[] stats, PermissionManager permissions,
                              ToolOutputStore spill) {
        int limit = Math.min(calls.size(), settings.maxToolCallsPerStep());
        List<PreparedCall> prepared = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            control.checkCancelled();
            ToolCall call = calls.get(i);
            PreparedCall item = new PreparedCall(call);
            prepared.add(item);
            if (i >= limit) {
                item.error = "Skipped: the limit is " + settings.maxToolCallsPerStep()
                        + " tool calls per model turn. Ask for this tool again in the next turn.";
                continue;
            }
            try {
                item.args = parseArguments(call.arguments());
            } catch (RuntimeException e) {
                item.error = "Invalid JSON arguments: " + e.getMessage();
                continue;
            }
            log.emit("tool.start", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "arguments", truncate(call.arguments(), 5000)
            ));
            item.permission = permissions.authorize(call.name(), item.args);
            if (!item.permission.allowed()) {
                item.error = "Permission denied: " + item.permission.reason();
            }
        }

        long executable = prepared.stream().filter(PreparedCall::executable).count();
        boolean canParallel = executable >= 2
                && prepared.stream().filter(PreparedCall::executable)
                        .allMatch(item -> tools.concurrencySafe(item.call.name())
                                && !item.permission.networkAllowed());
        if (canParallel) {
            runParallel(prepared, context, control);
        } else {
            for (PreparedCall item : prepared) {
                if (!item.executable()) {
                    continue;
                }
                context.setNetworkAllowed(item.permission.networkAllowed());
                long started = System.currentTimeMillis();
                try {
                    item.result = tools.execute(item.call.name(), item.args, context);
                } finally {
                    context.setNetworkAllowed(false);
                }
                item.duration = System.currentTimeMillis() - started;
            }
        }

        for (PreparedCall item : prepared) {
            if (!item.executable()) {
                messages.add(ChatMessage.tool(item.call.id(),
                        "ERROR " + item.call.name() + ": " + item.error));
                log.emit("tool.end", eventMap("id", item.call.id(), "name", item.call.name(),
                        "ok", false, "output", item.error, "durationMs", 0));
                stats[1]++;
                continue;
            }
            ToolResult result = item.result;
            if (result == null) {
                result = ToolResult.error("Tool " + item.call.name() + " was cancelled before it ran");
            }
            String output;
            try {
                ToolOutputRef ref = spill.save(state.id(), log.lastSeq(), item.call.name(), result.output());
                output = spill.inline(result.output(), ref);
            } catch (WorkspaceException e) {
                output = truncate(result.output(), MAX_TOOL_OUTPUT_CHARS)
                        + "\n[spill failed: " + e.getMessage() + "]";
            }
            messages.add(ChatMessage.tool(item.call.id(),
                    (result.ok() ? "OK " : "ERROR ") + item.call.name() + "\n" + output));
            if (result.ok()) {
                stats[0]++;
            } else {
                stats[1]++;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", item.call.id());
            event.put("name", item.call.name());
            event.put("ok", result.ok());
            event.put("output", output);
            event.put("durationMs", item.duration);
            event.put("data", result.data());
            log.emit("tool.end", event);
            if (item.call.name().equals("update_plan")) {
                log.emit("plan", eventMap("plan", state.plan()));
            }
            Log.info("agent.tool", Map.of(
                    "runId", state.id(),
                    "tool", item.call.name(),
                    "ok", result.ok(),
                    "durationMs", item.duration
            ));
        }
        context.refreshFiles();
        control.checkCancelled();
    }

    private void runParallel(List<PreparedCall> prepared, ToolContext context, RunControl control) {
        List<PreparedCall> runnable = prepared.stream().filter(PreparedCall::executable).toList();
        int workers = Math.min(4, runnable.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (PreparedCall item : runnable) {
                if (control.isCancelled()) {
                    break;
                }
                futures.add(pool.submit(() -> {
                    if (control.isCancelled()) {
                        return;
                    }
                    long started = System.currentTimeMillis();
                    try {
                        item.result = tools.execute(item.call.name(), item.args, context);
                    } catch (RuntimeException e) {
                        item.result = ToolResult.error("Tool " + item.call.name() + " failed: " + e.getMessage());
                    }
                    item.duration = System.currentTimeMillis() - started;
                }));
            }
            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RunControl.RunCancelledException();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("Parallel tool worker failed", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
            boolean interrupted = false;
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        control.checkCancelled();
    }

    private static final class PreparedCall {
        private final ToolCall call;
        private Map<String, Object> args;
        private PermissionResult permission;
        private String error;
        private ToolResult result;
        private long duration;

        private PreparedCall(ToolCall call) {
            this.call = call;
        }

        private boolean executable() {
            return error == null && permission != null && permission.allowed();
        }
    }

    /**
     * Calls the model and, on a context-window rejection, compacts aggressively
     * and retries exactly once. Any other failure keeps today's fail-fast path.
     */
    private ChatResult requestModel(ChatRequest request, List<ChatMessage> messages,
                                    RunState state, RunEventLog log, RunControl control,
                                    int cycle) {
        boolean recovered = false;
        while (true) {
            try {
                return streamModel(request);
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
                int beforeChars = compactor.chars(messages);
                int beforeTokens = compactor.tokens(messages);
                log.emit("context.overflow", eventMap(
                        "step", cycle,
                        "message", e.getMessage(),
                        "code", e.code(),
                        "beforeChars", beforeChars,
                        "beforeTokens", beforeTokens
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

    private boolean verifyNow(ToolContext context, RunState state, RunEventLog log, int cycle) {
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

    private String verificationFeedback(String summary) {
        return "Automatic verification failed before the task can finish: " + summary + "\n"
                + "Inspect the exact compiler/interpreter output, fix the source with edit_file or write_file, "
                + "then call compile_code or run_code again. Do not claim success until the real toolchain passes.";
    }

    private String goalFeedback(GoalReview review) {
        StringBuilder out = new StringBuilder();
        if (!review.missing().isEmpty()) {
            out.append("Missing requirements: ").append(String.join("; ", review.missing())).append(". ");
        }
        if (!review.issues().isEmpty()) {
            out.append("Issues: ").append(String.join("; ", review.issues())).append(". ");
        }
        return out.length() == 0 ? "The final answer does not satisfy the original task." : out.toString().trim();
    }

    private String buildSystemPrompt(String task, Workspace workspace, List<Skill> selected) {
        StringBuilder out = new StringBuilder();
        String goalSection = settings.goalMode() ? """
                ## Goal mode
                The original task is immutable and is repeated here:
                %s

                Before your final answer, check the current plan and the original task. Mark every
                completed plan item with update_plan, and make the final answer map the requested
                deliverables to concrete evidence (files and commands). The framework runs an
                independent goal review; if it finds missing requirements, you will get one more
                chance to fix them.

                """.formatted(task) : "";
        out.append("""
                You are Jade, a local coding agent. You work on a real machine and your file edits
                are really written to disk. You must make the artifact compile, run, and be tested.

                ## Supported languages
                - Java: .java; javac or Maven; entry is a main class.
                - Python: .py; python3; entry is a relative file such as main.py.
                - JavaScript: .js; Node.js; entry is a relative file such as main.js.
                - Go: .go; go build ./... and go run .; entry defaults to the package (.).
                - Choose the language the user asks for. For a new program, use the requested language;
                  do not default to Java. Keep one primary language per workspace unless asked to mix.
                - Never install an SDK or toolchain inside the workspace. Use the runtime already on PATH;
                  .toolchain, vendor, and third_party trees are ignored by verification.

                ## Hard rules
                - Use the provided tools for every file read, write, and command. Never claim an action you did not take.
                - Use relative paths inside the workspace. The workspace root is: %s
                - Keep changes focused. Read files before editing them; prefer edit_file over rewriting.
                - Maintain the visible plan with update_plan at the start and after milestones.
                - Compile or syntax-check with compile_code, then run with run_code. Both detect the language
                  automatically; pass language=java/python/javascript/go when you need to be explicit.
                - compile_java and run_java remain available as Java-only aliases. Use run_command for tests
                  such as `mvn test`, `pytest`, or `go test ./...`.
                - If you are stuck or repeating the same inspection, call `reflect` with diagnosis,
                  evidence, attempts, one next_action, and should_stop. Do not keep slicing the same file
                  with different line ranges. The framework asks for reflection after repeated no-progress turns.
                - If a genuine product decision is ambiguous (API shape, data model, tradeoff, destructive
                  direction), call `ask_user` with one question, optional detail, mode single/multiple/text,
                  and 2-5 short options. Do not ask for facts you can inspect yourself, and do not use it
                  merely to confirm routine work.
                - Before your final answer, the framework automatically verifies the workspace if any source
                  changed. If verification fails, the failure is fed back to you. Fix it, then finish.
                - If an installed skill is relevant, load it with load_skill. Skills are instructions, not tools.
                - Keep the final answer concise: what changed, the verification command/result, and how to run it.

                %s
                ## Installed skill catalog
                %s

                ## Auto-selected skills (full instructions)
                %s

                ## Ten-phase turn
                Every model turn runs ten phases: Intake, Context, Skills, Plan, Model, Tools, Observe,
                Verify, Compact, Checkpoint. You only see the Model/Tools loop; the framework handles the rest.
                """.formatted(
                workspace.root(),
                goalSection,
                skills.catalog(),
                selected.isEmpty() ? "(none pre-selected; use load_skill if needed)"
                        : String.join("\n\n", selected.stream().map(Skill::render).toList())
        ));
        return out.toString();
    }

    private String buildInitialUserMessage(String task, Workspace workspace) {
        return """
                TASK
                %s

                WORKSPACE TREE (initially; .agent and build directories are hidden)
                %s
                """.formatted(task, workspace.tree(120, 3));
    }

    private void phase(RunState state, RunEventLog log, AgentPhase phase, int cycle, String detail) {
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

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getOriginalMessage());
        }
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
    /** Child runs keep their transcript in a subdirectory of the shared workspace. */
    private static String persistArtifacts(Workspace workspace, RunState state,
                                           java.util.List<ChatMessage> messages, RunEventLog log) {
        String dir = state.isSubagent() ? ".agent/subagents/" + state.id() : ".agent";
        return RunArtifacts.persist(workspace, state.snapshot(), messages, log, dir);
    }

}
