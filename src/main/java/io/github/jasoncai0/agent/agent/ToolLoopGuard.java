package io.github.jasoncai0.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.llm.ToolCall;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stops two kinds of tool loops:
 * <ul>
 *   <li>the exact same call four times in a row;</li>
 *   <li>the same command <em>shape</em> eight times in the last ten calls,
 *       with numeric arguments normalised. This catches bytecode/line-range
 *       loops such as {@code javap ... | sed -n '60,80p'} followed by
 *       {@code sed -n '80,100p'}.</li>
 * </ul>
 * The shape window is deliberately wider than the exact-repeat window so a
 * reflection checkpoint can happen first.
 */
final class ToolLoopGuard {
    static final int REPEAT_LIMIT = 4;
    static final int SHAPE_WINDOW = 10;
    static final int SHAPE_LIMIT = 8;
    private static final ObjectMapper JSON = new ObjectMapper();

    private String lastSignature;
    private int repeats;
    private final Deque<String> shapes = new ArrayDeque<>();

    /** Returns a human-readable reason when a repeat limit is crossed. */
    String observe(ToolCall call) {
        String signature = call.name() + "\u0000" + canonical(call.arguments());
        if (signature.equals(lastSignature)) {
            repeats++;
        } else {
            lastSignature = signature;
            repeats = 1;
        }
        if (repeats >= REPEAT_LIMIT) {
            return "The same tool call (" + call.name() + ") was repeated " + repeats
                    + " times with identical arguments. The loop guard stopped the run; "
                    + "inspect the trace and send a follow-up with a different strategy.";
        }

        String shape = commandShape(call);
        if (shape != null) {
            shapes.addLast(shape);
            while (shapes.size() > SHAPE_WINDOW) {
                shapes.removeFirst();
            }
            int count = 0;
            for (String item : shapes) {
                if (item.equals(shape)) {
                    count++;
                }
            }
            if (count >= SHAPE_LIMIT) {
                return "The same command shape (" + call.name() + ") appeared " + count
                        + " times in the last " + SHAPE_WINDOW + " tool calls, with only numeric "
                        + "arguments changing. The loop guard stopped the run; reflect on the "
                        + "hypothesis or ask for input instead of slicing the same input again.";
            }
        }
        return null;
    }

    void reset() {
        lastSignature = null;
        repeats = 0;
        shapes.clear();
    }

    private static String commandShape(ToolCall call) {
        if (!"run_command".equals(call.name())) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(call.arguments());
            String command = root.path("command").asText("");
            if (command.isBlank()) {
                return null;
            }
            return call.name() + "\u0000" + command
                    .replaceAll("\\d+", "#")
                    .replaceAll("\\s+", " ")
                    .trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String canonical(String arguments) {
        if (arguments == null) {
            return "";
        }
        return arguments.replaceAll("\\s+", "");
    }
}
