package io.github.jasoncai0.agent.config;

public record LlmSettings(
        String apiKey,
        String baseUrl,
        String model,
        int timeoutSeconds,
        int maxOutputTokens
) {
    public boolean hasApiKey() {
        return apiKey != null
                && !apiKey.isBlank()
                && !apiKey.startsWith("sk-your-key-here");
    }

    public String endpoint() {
        return endpoint(false);
    }

    /**
     * Strict tool calling is a DeepSeek beta feature. Route the official base URL
     * through /beta automatically; custom gateways keep their configured path.
     */
    public String endpoint(boolean beta) {
        String normalized = baseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (beta && normalized.matches("https?://api\\.deepseek\\.com")) {
            return normalized + "/beta/chat/completions";
        }
        return normalized + "/chat/completions";
    }
}
