package io.github.jasoncai0.agent.llm;

public interface LlmClient {
    ChatResult chat(ChatRequest request) throws LlmException;

    /**
     * Receives incremental output while {@link #stream(ChatRequest, StreamListener)}
     * is running. Deltas are advisory: the returned {@link ChatResult} is the
     * only source of truth. Callers must not persist deltas.
     */
    interface StreamListener {
        void onText(String delta);

        void onReasoning(String delta);

        void onToolCallDelta(int index, String id, String name, String argumentsDelta);

        void onUsage(Usage usage);

        static StreamListener noop() {
            return new StreamListener() {
                @Override
                public void onText(String delta) {
                }

                @Override
                public void onReasoning(String delta) {
                }

                @Override
                public void onToolCallDelta(int index, String id, String name, String argumentsDelta) {
                }

                @Override
                public void onUsage(Usage usage) {
                }
            };
        }
    }

    /**
     * Streaming variant of {@link #chat(ChatRequest)}. The default implementation
     * falls back to a single blocking call and replayes its result as one delta,
     * so every client stays streamable without implementing SSE.
     */
    default ChatResult stream(ChatRequest request, StreamListener listener) throws LlmException {
        StreamListener target = listener == null ? StreamListener.noop() : listener;
        ChatResult result = chat(request);
        if (result.content() != null && !result.content().isEmpty()) {
            target.onText(result.content());
        }
        if (result.reasoningContent() != null && !result.reasoningContent().isEmpty()) {
            target.onReasoning(result.reasoningContent());
        }
        if (result.usage() != null && result.usage().totalTokens() > 0) {
            target.onUsage(result.usage());
        }
        return result;
    }

    /**
     * Returns the exact JSON body that {@link #chat(ChatRequest)} sends, for local
     * tracing. Implementations that cannot expose it return {@code null}. The body
     * never contains credentials; authorization headers are outside this payload.
     */
    default String requestBody(ChatRequest request) {
        return null;
    }

    /**
     * Same payload the streaming call sends. The default keeps the non-streaming
     * body so traces stay honest for clients without SSE support.
     */
    default String requestBody(ChatRequest request, boolean stream) {
        return requestBody(request);
    }
}
