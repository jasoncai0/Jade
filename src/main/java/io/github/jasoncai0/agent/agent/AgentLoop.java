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
import io.github.jasoncai0.agent.skill.Skill;
import io.github.jasoncai0.agent.skill.SkillRegistry;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One run of the agent. Every model turn executes the same ten phases; the hard
 * model-turn cap is AgentSettings.maxSteps (default 10).
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
    private final ContextCompactor compactor = new ContextCompactor();

    public AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
                     AgentSettings settings, int maxOutputTokens) {
        this(llm, tools, skills, settings, maxOutputTokens, new Verifier());
    }

    AgentLoop(LlmClient llm, ToolRegistry tools, SkillRegistry skills,
              AgentSettings settings, int maxOutputTokens, Verifier verifier) {
        this.llm = llm;
        this.tools = tools;
        this.skills = skills;
        this.settings = settings;
        this.maxOutputTokens = maxOutputTokens;
        this.verifier = verifier;
    }

    public RunSnapshot run(String task, Workspace workspace, RunState state,
                           RunEventLog log, RunControl control) {
        state.markRunning();
        Log.info("agent.run.start", Map.of(
                "runId", state.id(),
                "workspace", workspace.root().getFileName().toString(),
                "taskChars", task.length()
        ));
        log.emit("run.started", eventMap("snapshot", state.snapshot()));

        ToolContext context = new ToolContext(workspace, settings, skills, state, verifier);
        List<Skill> selected = skills.select(task, 2);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(workspace, selected)));
        messages.add(ChatMessage.user(buildInitialUserMessage(task, workspace)));
        context.refreshFiles();

        try {
            boolean completed = false;
            int totalToolCalls = 0;
            for (int cycle = 1; cycle <= settings.maxSteps(); cycle++) {
                control.checkCancelled();
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
                String requestBody = llm.requestBody(request);
                if (requestBody != null && !requestBody.isBlank()) {
                    log.emit("llm.request", eventMap(
                            "body", requestBody,
                            "step", cycle,
                            "messages", request.messages().size(),
                            "tools", request.tools().size(),
                            "chars", requestBody.length()
                    ));
                }
                ChatResult result;
                try {
                    result = llm.chat(request);
                } catch (LlmException e) {
                    if (control.isCancelled()) {
                        throw new RunControl.RunCancelledException();
                    }
                    throw e;
                }
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
                    totalToolCalls += result.toolCalls().size();
                    executeTools(result.toolCalls(), messages, context, state, log, control, stats);
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
                ContextCompactor.Result compaction = compactor.compact(messages, settings.contextCharBudget());
                if (compaction.compacted()) {
                    messages.clear();
                    messages.addAll(compaction.messages());
                    log.emit("compaction", eventMap(
                            "removed", compaction.removed(),
                            "beforeChars", compaction.beforeChars(),
                            "afterChars", compaction.afterChars()
                    ));
                }

                context.refreshFiles();
                String transcript = RunArtifacts.persist(workspace, state.snapshot(), messages, log);
                state.setTranscriptPath(transcript);
                phase(state, log, AgentPhase.CHECKPOINT, cycle,
                        state.files().size() + " workspace entries; transcript " + (transcript == null ? "unavailable" : transcript));

                if (!hasCalls && verified) {
                    String answer = result.content() == null || result.content().isBlank()
                            ? "Task completed. " + state.verification()
                            : result.content();
                    state.succeed(answer);
                    log.emit("run.succeeded", eventMap("answer", answer, "usage", state.usage()));
                    completed = true;
                    break;
                }
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
                String transcript = RunArtifacts.persist(workspace, state.snapshot(), messages, log);
                state.setTranscriptPath(transcript);
            } catch (RuntimeException e) {
                Log.warn("agent.artifacts.failed", Map.of("runId", state.id(), "error", String.valueOf(e.getMessage())));
            }
            RunSnapshot snapshot = state.snapshot();
            log.emit("run.finished", eventMap("snapshot", snapshot));
            log.close();
            try {
                // Persist once more so a restarted process can replay the final
                // run.finished event, not just the earlier checkpoint.
                RunArtifacts.persist(workspace, snapshot, messages, log);
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

    private void executeTools(List<ToolCall> calls, List<ChatMessage> messages,
                              ToolContext context, RunState state, RunEventLog log,
                              RunControl control, int[] stats) {
        int limit = Math.min(calls.size(), settings.maxToolCallsPerStep());
        for (int i = 0; i < calls.size(); i++) {
            control.checkCancelled();
            ToolCall call = calls.get(i);
            if (i >= limit) {
                String message = "Skipped: the limit is " + settings.maxToolCallsPerStep()
                        + " tool calls per model turn. Ask for this tool again in the next turn.";
                messages.add(ChatMessage.tool(call.id(), "ERROR " + call.name() + ": " + message));
                log.emit("tool.end", eventMap("id", call.id(), "name", call.name(), "ok", false,
                        "output", message, "durationMs", 0));
                stats[1]++;
                continue;
            }
            Map<String, Object> args;
            try {
                args = parseArguments(call.arguments());
            } catch (RuntimeException e) {
                String message = "Invalid JSON arguments: " + e.getMessage();
                messages.add(ChatMessage.tool(call.id(), "ERROR " + call.name() + ": " + message));
                log.emit("tool.end", eventMap("id", call.id(), "name", call.name(), "ok", false,
                        "output", message, "durationMs", 0));
                stats[1]++;
                continue;
            }
            log.emit("tool.start", eventMap(
                    "id", call.id(),
                    "name", call.name(),
                    "arguments", truncate(call.arguments(), 5000)
            ));
            long started = System.currentTimeMillis();
            ToolResult result = tools.execute(call.name(), args, context);
            long duration = System.currentTimeMillis() - started;
            String output = truncate(result.output(), MAX_TOOL_OUTPUT_CHARS);
            messages.add(ChatMessage.tool(call.id(),
                    (result.ok() ? "OK " : "ERROR ") + call.name() + "\n" + output));
            if (result.ok()) {
                stats[0]++;
            } else {
                stats[1]++;
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", call.id());
            event.put("name", call.name());
            event.put("ok", result.ok());
            event.put("output", output);
            event.put("durationMs", duration);
            event.put("data", result.data());
            log.emit("tool.end", event);
            if (call.name().equals("update_plan")) {
                log.emit("plan", eventMap("plan", state.plan()));
            }
            Log.info("agent.tool", Map.of(
                    "runId", state.id(),
                    "tool", call.name(),
                    "ok", result.ok(),
                    "durationMs", duration
            ));
            context.refreshFiles();
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

    private String buildSystemPrompt(Workspace workspace, List<Skill> selected) {
        StringBuilder out = new StringBuilder();
        out.append("""
                You are Jade, a local coding agent. You work on a real machine and your file edits
                are really written to disk. You must make the artifact compile, run, and be tested.

                ## Supported languages
                - Java: .java; javac or Maven; entry is a main class.
                - Python: .py; python3; entry is a relative file such as main.py.
                - JavaScript: .js; Node.js; entry is a relative file such as main.js.
                - Choose the language the user asks for. For a new program, use the requested language;
                  do not default to Java. Keep one primary language per workspace unless asked to mix.

                ## Hard rules
                - Use the provided tools for every file read, write, and command. Never claim an action you did not take.
                - Use relative paths inside the workspace. The workspace root is: %s
                - Keep changes focused. Read files before editing them; prefer edit_file over rewriting.
                - Maintain the visible plan with update_plan at the start and after milestones.
                - Compile or syntax-check with compile_code, then run with run_code. Both detect the language
                  automatically; pass language=java/python/javascript when you need to be explicit.
                - compile_java and run_java remain available as Java-only aliases. Use run_command for tests
                  such as `mvn test` or `python3 -m pytest`.
                - Before your final answer, the framework automatically verifies the workspace if any source
                  changed. If verification fails, the failure is fed back to you. Fix it, then finish.
                - If an installed skill is relevant, load it with load_skill. Skills are instructions, not tools.
                - Keep the final answer concise: what changed, the verification command/result, and how to run it.

                ## Installed skill catalog
                %s

                ## Auto-selected skills (full instructions)
                %s

                ## Ten-phase turn
                Every model turn runs ten phases: Intake, Context, Skills, Plan, Model, Tools, Observe,
                Verify, Compact, Checkpoint. You only see the Model/Tools loop; the framework handles the rest.
                """.formatted(
                workspace.root(),
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
}
