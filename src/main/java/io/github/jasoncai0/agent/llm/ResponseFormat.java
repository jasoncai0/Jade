package io.github.jasoncai0.agent.llm;

/**
 * Response formats supported by the DeepSeek chat completions API.
 */
public enum ResponseFormat {
    TEXT("text"),
    JSON_OBJECT("json_object");

    private final String wireName;

    ResponseFormat(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
