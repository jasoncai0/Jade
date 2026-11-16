package io.github.jasoncai0.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.agent.RunControl;
import io.github.jasoncai0.agent.agent.RunEventLog;
import io.github.jasoncai0.agent.agent.RunState;
import io.github.jasoncai0.agent.llm.ToolSpec;
import io.github.jasoncai0.agent.question.QuestionAnswer;
import io.github.jasoncai0.agent.question.QuestionBroker;
import io.github.jasoncai0.agent.question.QuestionMode;
import io.github.jasoncai0.agent.question.QuestionOption;
import io.github.jasoncai0.agent.question.QuestionRequest;
import io.github.jasoncai0.agent.tool.Schemas;
import io.github.jasoncai0.agent.tool.Tool;
import io.github.jasoncai0.agent.tool.ToolArgs;
import io.github.jasoncai0.agent.tool.ToolContext;
import io.github.jasoncai0.agent.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocks the agent until the user answers a decision card. The Web UI resolves
 * the request through the run API; the answer is returned as the tool result.
 */
public final class AskUserTool implements Tool {
    private static final Duration WAIT = Duration.ofMinutes(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "ask_user",
                "Ask the user to make a decision when the task is genuinely ambiguous or a destructive "
                        + "direction needs a choice. Provide one question, optional detail, mode "
                        + "(single, multiple, text), and 2-5 short options for single/multiple. "
                        + "Use text mode for open input. Do not use this for facts you can inspect yourself.",
                Schemas.object(Schemas.properties(
                        "question", Schemas.string("One clear decision question."),
                        "detail", Schemas.string("Optional context or tradeoff."),
                        "mode", Schemas.stringEnum("single, multiple, or text.", List.of("single", "multiple", "text")),
                        "options", Schemas.array("Short choices for single/multiple; omit for text.",
                                Schemas.string("One choice label."))
                ), "question", "mode")
        );
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> args) {
        QuestionBroker broker = context.questions();
        RunEventLog events = context.events();
        RunState state = context.state();
        RunControl control = context.control();
        if (broker == null || events == null || state == null) {
            return ToolResult.error("ask_user is unavailable in this execution context");
        }

        String question = ToolArgs.requiredString(args, "question");
        String detail = ToolArgs.string(args, "detail", "");
        QuestionMode mode = QuestionMode.fromId(ToolArgs.string(args, "mode", "single"));
        List<QuestionOption> options = new ArrayList<>();
        if (mode != QuestionMode.TEXT) {
            List<Object> raw = ToolArgs.list(args, "options");
            if (raw.isEmpty() || raw.size() > 8) {
                return ToolResult.error("ask_user requires 1-8 options for single/multiple mode");
            }
            for (int i = 0; i < raw.size(); i++) {
                String label = String.valueOf(raw.get(i)).strip();
                if (label.isEmpty()) {
                    return ToolResult.error("ask_user option labels must not be blank");
                }
                options.add(new QuestionOption("opt-" + (i + 1), label));
            }
        }

        QuestionRequest request = QuestionRequest.create(state.id(), question, detail, mode, options,
                WAIT.toMillis());
        broker.register(request);
        state.awaitQuestion(request);
        events.emit("question.request", request.toEventData());

        QuestionAnswer answer;
        try {
            answer = broker.await(request, WAIT);
        } catch (RuntimeException e) {
            answer = QuestionAnswer.cancelled(request.id());
        } finally {
            state.resolveQuestion();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", request.id());
        data.put("mode", mode.id());
        data.put("selected", answer.selected());
        data.put("text", answer.text());
        data.put("timedOut", answer.timedOut());
        data.put("cancelled", answer.cancelled());

        Map<String, Object> resolved = new LinkedHashMap<>(data);
        resolved.put("question", question);
        events.emit("question.resolved", resolved);
        appendAudit(context, request, answer);

        if (control != null && control.isCancelled()) {
            throw new RunControl.RunCancelledException();
        }
        if (answer.cancelled()) {
            return new ToolResult(false, "The question was cancelled before the user answered.", data);
        }
        if (answer.timedOut()) {
            return new ToolResult(false, "No answer was received within 30 minutes. Continue with a safe default "
                    + "or ask again if the decision is still necessary.", data);
        }
        String output;
        if (mode == QuestionMode.TEXT) {
            output = "User answered:\n" + answer.text();
        } else {
            List<String> labels = answer.selected().stream()
                    .map(id -> options.stream().filter(o -> o.id().equals(id)).findFirst()
                            .map(QuestionOption::label).orElse(id))
                    .toList();
            output = "User selected: " + String.join(", ", labels)
                    + " (ids: " + String.join(", ", answer.selected()) + ")";
        }
        return new ToolResult(true, output, data);
    }

    private void appendAudit(ToolContext context, QuestionRequest request, QuestionAnswer answer) {
        try {
            Path file = context.workspace().root().resolve(".agent/questions.jsonl");
            Files.createDirectories(file.getParent());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", System.currentTimeMillis());
            row.put("requestId", request.id());
            row.put("mode", request.mode().id());
            row.put("question", request.question());
            row.put("selected", answer.selected());
            row.put("text", answer.text());
            row.put("timedOut", answer.timedOut());
            row.put("cancelled", answer.cancelled());
            Files.writeString(file, JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean mutatesWorkspace() {
        return false;
    }
}
