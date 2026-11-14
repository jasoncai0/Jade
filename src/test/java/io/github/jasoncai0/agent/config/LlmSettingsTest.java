package io.github.jasoncai0.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmSettingsTest {
    @Test
    void routesOfficialBaseUrlThroughBetaForStrictTools() {
        LlmSettings settings = settings("https://api.deepseek.com");

        assertEquals("https://api.deepseek.com/chat/completions", settings.endpoint(false));
        assertEquals("https://api.deepseek.com/beta/chat/completions", settings.endpoint(true));
    }

    @Test
    void doesNotDoubleBetaOrRewriteCustomGateways() {
        assertEquals("https://api.deepseek.com/beta/chat/completions",
                settings("https://api.deepseek.com/beta").endpoint(true));
        assertEquals("http://127.0.0.1:9000/v1/chat/completions",
                settings("http://127.0.0.1:9000/v1").endpoint(true));
    }

    private static LlmSettings settings(String baseUrl) {
        return new LlmSettings("sk-test", baseUrl, "deepseek-chat", 10, 1024);
    }
}
