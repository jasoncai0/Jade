package io.github.jasoncai0.agent.llm;

public record Usage(int promptTokens, int completionTokens, int totalTokens) {
    public static final Usage ZERO = new Usage(0, 0, 0);

    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens
        );
    }
}
