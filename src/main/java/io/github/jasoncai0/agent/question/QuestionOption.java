package io.github.jasoncai0.agent.question;

public record QuestionOption(String id, String label) {
    public QuestionOption {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Option id is required");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Option label is required");
        }
        id = id.strip();
        label = label.strip();
    }
}
