package io.github.jasoncai0.agent.cli;

import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.RunSnapshot;

import java.io.PrintStream;
import java.util.Map;

/**
 * Human-readable headless output. JSON logs are disabled in this mode.
 */
public final class ConsoleRenderer {
    private final PrintStream out;

    public ConsoleRenderer(PrintStream out) {
        this.out = out;
    }

    public void render(AgentEvent event) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case "phase" -> out.printf("[%2s/%2s] %-9s %s%n",
                    text(data.get("step")), text(data.get("maxSteps")),
                    text(data.get("label")), text(data.get("detail")));
            case "assistant" -> {
                String content = text(data.get("content")).strip();
                if (!content.isEmpty()) {
                    out.println();
                    out.println("assistant>");
                    out.println(indent(truncate(content, 6000), "  "));
                    out.println();
                }
            }
            case "tool.start" -> out.printf("  -> %s %s%n",
                    text(data.get("name")), oneLine(text(data.get("arguments")), 500));
            case "tool.end" -> {
                String status = Boolean.TRUE.equals(data.get("ok")) ? "ok " : "ERR";
                out.printf("  <- %s %s (%sms) %s%n",
                        status, text(data.get("name")), text(data.get("durationMs")),
                        oneLine(text(data.get("output")), 900));
            }
            case "verification" -> {
                boolean skipped = Boolean.TRUE.equals(data.get("skipped"));
                boolean ok = Boolean.TRUE.equals(data.get("ok"));
                out.printf("%n[verify] %s%s%n  %s%n",
                        skipped ? "SKIP " : ok ? "PASS " : "FAIL ",
                        oneLine(text(data.get("summary")), 1000),
                        oneLine(text(data.get("command")), 500));
                String output = text(data.get("output"));
                if (!output.isBlank() && !ok) {
                    out.println(indent(truncate(output, 4000), "  "));
                }
            }
            case "permission.request" -> out.printf("%n[permission] %s: %s%n  %s%n",
                    text(data.get("kind")), text(data.get("summary")), text(data.get("detail")));
            case "permission.resolved" -> out.printf("[permission] %s (%s)%n",
                    text(data.get("decision")), text(data.get("scope")));
            case "compaction" -> out.printf("[compact] %s -> %s chars, removed %s messages%n",
                    text(data.get("beforeChars")), text(data.get("afterChars")), text(data.get("removed")));
            case "goal.review" -> {
                boolean aligned = Boolean.TRUE.equals(data.get("aligned"));
                out.printf("%n[goal] %s%n", aligned ? "PASS: final answer aligns with the original task" : "REJECT: final answer does not align");
                Object missing = data.get("missing");
                Object issues = data.get("issues");
                if (!aligned) {
                    if (missing != null && !String.valueOf(missing).isBlank() && !String.valueOf(missing).equals("[]")) {
                        out.println("  missing: " + oneLine(String.valueOf(missing), 1000));
                    }
                    if (issues != null && !String.valueOf(issues).isBlank() && !String.valueOf(issues).equals("[]")) {
                        out.println("  issues:  " + oneLine(String.valueOf(issues), 1000));
                    }
                }
            }
            case "run.failed" -> out.printf("%n[run failed] %s%n", text(data.get("error")));
            case "run.cancelled" -> out.printf("%n[run cancelled]%n");
            case "run.max_steps" -> out.printf("%n[run stopped] %s%n", text(data.get("message")));
            default -> {
                // usage, plan, workspace, checkpoint and lifecycle events are reflected in the final summary
            }
        }
    }

    public void finalSummary(RunSnapshot snapshot) {
        out.println();
        out.println("=".repeat(72));
        out.printf("status      %s%n", snapshot.status());
        out.printf("run         %s%n", snapshot.id());
        out.printf("workspace   %s%n", snapshot.workspace());
        out.printf("turns       %d / %d%n", snapshot.step(), snapshot.maxSteps());
        out.printf("tokens      prompt=%d completion=%d total=%d%n",
                snapshot.usage().promptTokens(),
                snapshot.usage().completionTokens(),
                snapshot.usage().totalTokens());
        if (snapshot.verification() != null) {
            out.printf("verify      %s%n", oneLine(snapshot.verification(), 1000));
        }
        if (snapshot.transcriptPath() != null) {
            out.printf("transcript  %s%n", snapshot.transcriptPath());
        }
        if (snapshot.error() != null) {
            out.printf("error       %s%n", snapshot.error());
        }
        if (snapshot.finalAnswer() != null) {
            out.println();
            out.println(truncate(snapshot.finalAnswer().strip(), 8000));
        }
        out.println("=".repeat(72));
    }

    public void info(String text) {
        out.println(text);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String oneLine(String value, int max) {
        String compact = truncate(value, max).replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").strip();
        return compact;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String indent(String value, String prefix) {
        return prefix + value.replace("\n", "\n" + prefix);
    }
}
