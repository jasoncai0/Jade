package io.github.jasoncai0.agent.llm;

public interface LlmClient {
    ChatResult chat(ChatRequest request) throws LlmException;

    /**
     * Returns the exact JSON body that {@link #chat(ChatRequest)} sends, for local
     * tracing. Implementations that cannot expose it return {@code null}. The body
     * never contains credentials; authorization headers are outside this payload.
     */
    default String requestBody(ChatRequest request) {
        return null;
    }
}
