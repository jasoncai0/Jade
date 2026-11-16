package io.github.jasoncai0.agent.question;

import java.util.List;

public record QuestionAnswer(String requestId, List<String> selected, String text,
                             boolean timedOut, boolean cancelled) {
    public QuestionAnswer {
        selected = selected == null ? List.of() : List.copyOf(selected);
        text = text == null ? "" : text.strip();
    }

    public static QuestionAnswer answer(String requestId, List<String> selected, String text) {
        return new QuestionAnswer(requestId, selected, text, false, false);
    }

    public static QuestionAnswer timeout(String requestId) {
        return new QuestionAnswer(requestId, List.of(), "", true, false);
    }

    public static QuestionAnswer cancelled(String requestId) {
        return new QuestionAnswer(requestId, List.of(), "", false, true);
    }

    public boolean answered() {
        return !timedOut && !cancelled && (!selected.isEmpty() || !text.isBlank());
    }
}
