package io.github.jasoncai0.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.LlmSettings;
import io.github.jasoncai0.agent.config.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DeepSeek's OpenAI-compatible chat completions API, including function/tool calling.
 */
public final class DeepSeekClient implements LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 3;
    private static final int ERROR_BODY_LIMIT = 8_192;
    private static final String DATA_PREFIX = "data:";

    private final LlmSettings settings;
    private final HttpClient http;
    private final Duration retryBaseDelay;
    private final boolean includeUsageByDefault;

    public DeepSeekClient(LlmSettings settings) {
        this(settings, Duration.ofMillis(700));
    }

    DeepSeekClient(LlmSettings settings, Duration retryBaseDelay) {
        this(settings, retryBaseDelay, false);
    }

    /**
     * Test seam: {@code includeUsageByDefault} lets tests exercise the
     * {@code stream_options} fallback without pointing at api.deepseek.com.
     */
    DeepSeekClient(LlmSettings settings, Duration retryBaseDelay, boolean includeUsageByDefault) {
        this.includeUsageByDefault = includeUsageByDefault;
        this.settings = settings;
        this.retryBaseDelay = retryBaseDelay;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String requestBody(ChatRequest request) {
        return buildRequestBody(request, false, false);
    }

    @Override
    public String requestBody(ChatRequest request, boolean stream) {
        return buildRequestBody(request, stream, stream && usesOfficialEndpoint());
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        if (!settings.hasApiKey()) {
            throw new LlmException("DEEPSEEK_API_KEY is not set. Copy .env.example to .env and add your key.");
        }
        String body = requestBody(request);
        URI endpoint = URI.create(settings.endpoint(request.hasStrictTools()));
        LlmException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                        .header("Authorization", "Bearer " + settings.apiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("User-Agent", "java-coding-agent/" + AppConfig.VERSION)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                Log.info("llm.request", Map.of(
                        "model", settings.model(),
                        "messages", request.messages().size(),
                        "tools", request.tools().size(),
                        "attempt", attempt
                ));
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                int status = response.statusCode();

                if (status == 200) {
                    ChatResult result = parseResponse(response.body());
                    Log.info("llm.response", Map.of(
                            "model", settings.model(),
                            "finishReason", String.valueOf(result.finishReason()),
                            "toolCalls", result.toolCalls().size(),
                            "promptTokens", result.usage().promptTokens(),
                            "completionTokens", result.usage().completionTokens(),
                            "elapsedMs", elapsedMs
                    ));
                    return result;
                }

                String message = extractErrorMessage(response.body(), status);
                String code = extractErrorCode(response.body());
                lastFailure = new LlmException(message, status, code, null);
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    throw lastFailure;
                }
                long delayMs = retryDelayMs(attempt, response.headers().firstValue("Retry-After").orElse(null));
                Log.warn("llm.retry", Map.of(
                        "status", status,
                        "attempt", attempt,
                        "delayMs", delayMs
                ));
                sleep(delayMs);
            } catch (HttpTimeoutException e) {
                lastFailure = new LlmException("DeepSeek request timed out after "
                        + settings.timeoutSeconds() + "s", 0, e);
                if (attempt == MAX_ATTEMPTS) {
                    throw lastFailure;
                }
                Log.warn("llm.timeout", Map.of("attempt", attempt));
                sleep(retryDelayMs(attempt, null));
            } catch (IOException e) {
                lastFailure = new LlmException("Cannot reach DeepSeek: " + e.getMessage(), 0, e);
                if (attempt == MAX_ATTEMPTS) {
                    throw lastFailure;
                }
                Log.warn("llm.io_error", Map.of("attempt", attempt, "error", String.valueOf(e.getMessage())));
                sleep(retryDelayMs(attempt, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("DeepSeek request interrupted", 0, e);
            }
        }
        throw lastFailure == null ? new LlmException("DeepSeek request failed") : lastFailure;
    }

     @Override
    public ChatResult stream(ChatRequest request, LlmClient.StreamListener listener) {
        if (!settings.hasApiKey()) {
            throw new LlmException("DEEPSEEK_API_KEY is not set. Copy .env.example to .env and add your key.");
        }
        LlmClient.StreamListener target = listener == null ? LlmClient.StreamListener.noop() : listener;
        URI endpoint = URI.create(settings.endpoint(request.hasStrictTools()));
        boolean includeUsage = includeUsageByDefault || usesOfficialEndpoint();
        LlmException lastFailure = null;
        int attempt = 1;
        while (attempt <= MAX_ATTEMPTS) {
            String body = buildRequestBody(request, true, includeUsage);
            long start = System.nanoTime();
            try {
                HttpRequest httpRequest = newRequest(endpoint, body, "text/event-stream");
                Log.info("llm.stream_request", Map.of(
                        "model", settings.model(),
                        "messages", request.messages().size(),
                        "tools", request.tools().size(),
                        "attempt", attempt
                ));
                HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                int status = response.statusCode();
                if (status != 200) {
                    String errorBody = readErrorBody(response.body());
                    String message = extractErrorMessage(errorBody, status);
                    if (status == 400 && includeUsage && mentionsStreamOptions(message)) {
                        includeUsage = false;
                        Log.warn("llm.stream_options_rejected", Map.of("attempt", attempt));
                        continue;
                    }
                    lastFailure = new LlmException(message, status, extractErrorCode(errorBody), null);
                    boolean retryable = status == 429 || status >= 500;
                    if (!retryable || attempt == MAX_ATTEMPTS) {
                        throw lastFailure;
                    }
                    long delayMs = retryDelayMs(attempt, response.headers().firstValue("Retry-After").orElse(null));
                    Log.warn("llm.retry", Map.of("status", status, "attempt", attempt, "delayMs", delayMs));
                    attempt++;
                    sleep(delayMs);
                    continue;
                }
                ChatResult result = parseStream(response.body(), target);
                Log.info("llm.stream_response", Map.of(
                        "model", settings.model(),
                        "finishReason", String.valueOf(result.finishReason()),
                        "toolCalls", result.toolCalls().size(),
                        "promptTokens", result.usage().promptTokens(),
                        "completionTokens", result.usage().completionTokens(),
                        "elapsedMs", elapsedMs
                ));
                return result;
            } catch (HttpTimeoutException e) {
                lastFailure = new LlmException("DeepSeek stream timed out after "
                        + settings.timeoutSeconds() + "s", 0, e);
                if (attempt == MAX_ATTEMPTS) {
                    throw lastFailure;
                }
                Log.warn("llm.timeout", Map.of("attempt", attempt));
                attempt++;
                sleep(retryDelayMs(attempt, null));
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmException("DeepSeek stream interrupted", 0, e);
                }
                lastFailure = new LlmException("DeepSeek stream failed: " + e.getMessage(), 0, e);
                if (attempt == MAX_ATTEMPTS) {
                    throw lastFailure;
                }
                Log.warn("llm.stream_io_error", Map.of("attempt", attempt,
                        "error", String.valueOf(e.getMessage())));
                attempt++;
                sleep(retryDelayMs(attempt, null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("DeepSeek stream interrupted", 0, e);
            }
        }
        throw lastFailure == null ? new LlmException("DeepSeek stream failed") : lastFailure;
    }

    private HttpRequest newRequest(URI endpoint, String body, String accept) {
        return HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("User-Agent", "java-coding-agent/" + AppConfig.VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private boolean usesOfficialEndpoint() {
        return settings.endpoint().contains("api.deepseek.com");
    }

    private static boolean mentionsStreamOptions(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("stream_options") || lower.contains("include_usage");
    }

    private static String readErrorBody(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (InputStream body = stream) {
            byte[] bytes = body.readNBytes(ERROR_BODY_LIMIT);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private ChatResult parseStream(InputStream stream, LlmClient.StreamListener listener) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallBuilder> calls = new TreeMap<>();
        Usage usage = Usage.ZERO;
        String finishReason = null;
        boolean sawChunk = false;
        boolean sawDone = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String data = line.substring(DATA_PREFIX.length()).strip();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    sawDone = true;
                    break;
                }
                JsonNode root;
                try {
                    root = JSON.readTree(data);
                } catch (IOException malformed) {
                    continue;
                }
                JsonNode error = root.get("error");
                if (error != null && !error.isNull()) {
                    String message = textOrNull(error.get("message"));
                    throw new LlmException(message == null ? "DeepSeek stream error" : message,
                            200, textOrNull(error.get("code")), null);
                }
                sawChunk = true;
                JsonNode usageNode = root.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    usage = new Usage(
                            usageNode.path("prompt_tokens").asInt(0),
                            usageNode.path("completion_tokens").asInt(0),
                            usageNode.path("total_tokens").asInt(0)
                    );
                    listener.onUsage(usage);
                }
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode choice = choices.get(0);
                String reason = textOrNull(choice.get("finish_reason"));
                if (reason != null && !reason.isBlank()) {
                    finishReason = reason;
                }
                JsonNode delta = choice.path("delta");
                String text = textOrNull(delta.get("content"));
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                    listener.onText(text);
                }
                String reasoningDelta = textOrNull(delta.get("reasoning_content"));
                if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    listener.onReasoning(reasoningDelta);
                }
                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        int index = call.path("index").asInt(0);
                        ToolCallBuilder builder = calls.computeIfAbsent(index, ToolCallBuilder::new);
                        String id = textOrNull(call.get("id"));
                        if (id != null && !id.isBlank()) {
                            builder.id = id;
                        }
                        JsonNode function = call.path("function");
                        String name = textOrNull(function.get("name"));
                        if (name != null && !name.isBlank()) {
                            builder.name = name;
                        }
                        String arguments = textOrNull(function.get("arguments"));
                        if (arguments != null) {
                            builder.arguments.append(arguments);
                        }
                        listener.onToolCallDelta(index, id, name, arguments);
                    }
                }
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new LlmException("DeepSeek stream interrupted", 0, e);
            }
            throw new LlmException("DeepSeek stream failed: " + e.getMessage(), 0, e);
        }
        if (!sawChunk) {
            throw new LlmException("DeepSeek returned an empty stream", 0, "EMPTY_RESPONSE", null);
        }
        if (!sawDone && finishReason == null) {
            throw new LlmException("DeepSeek stream ended before completion", 0, "STREAM_TRUNCATED", null);
        }
        List<ToolCall> merged = new ArrayList<>();
        calls.values().stream()
                .filter(builder -> builder.name != null && !builder.name.isBlank())
                .sorted(Comparator.comparingInt(builder -> builder.index))
                .forEach(builder -> merged.add(new ToolCall(
                        builder.id == null ? "call_" + builder.index : builder.id,
                        builder.name,
                        builder.arguments.length() == 0 ? "{}" : builder.arguments.toString()
                )));
        return new ChatResult(
                content.length() == 0 ? null : content.toString(),
                reasoning.length() == 0 ? null : reasoning.toString(),
                merged,
                finishReason,
                usage
        );
    }

    private static final class ToolCallBuilder {
        private final int index;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallBuilder(int index) {
            this.index = index;
        }
    }

    private String buildRequestBody(ChatRequest request, boolean stream, boolean includeUsage) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", settings.model());
        root.put("stream", stream);
        if (stream && includeUsage) {
            root.putObject("stream_options").put("include_usage", true);
        }
        root.put("temperature", request.temperature());
        root.put("max_tokens", request.maxTokens());
        if (request.responseFormat() != null) {
            root.putObject("response_format").put("type", request.responseFormat().wireName());
        }
        root.put("tool_choice", "auto");

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role().wireName());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.role() == Role.ASSISTANT
                    && message.reasoningContent() != null
                    && !message.reasoningContent().isBlank()) {
                node.put("reasoning_content", message.reasoningContent());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
        }

        ArrayNode tools = root.putArray("tools");
        for (ToolSpec tool : request.tools()) {
            ObjectNode toolNode = tools.addObject();
            toolNode.put("type", "function");
            ObjectNode function = toolNode.putObject("function");
            function.put("name", tool.name());
            if (tool.strict()) {
                function.put("strict", true);
            }
            function.put("description", tool.description());
            function.set("parameters", JSON.valueToTree(tool.parameters()));
        }
        return root.toString();
    }

    private ChatResult parseResponse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("DeepSeek returned no choices: " + truncate(body, 500));
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            String content = textOrNull(message.get("content"));
            String reasoning = textOrNull(message.get("reasoning_content"));
            String finishReason = textOrNull(choice.get("finish_reason"));

            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    JsonNode function = call.path("function");
                    String name = textOrNull(function.get("name"));
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    String arguments = textOrNull(function.get("arguments"));
                    calls.add(new ToolCall(
                            textOrNull(call.get("id")),
                            name,
                            arguments == null ? "{}" : arguments
                    ));
                }
            }
            JsonNode legacyCall = message.get("function_call");
            if (calls.isEmpty() && legacyCall != null && legacyCall.isObject()) {
                String name = textOrNull(legacyCall.get("name"));
                if (name != null && !name.isBlank()) {
                    calls.add(new ToolCall("legacy_call", name,
                            textOrNull(legacyCall.get("arguments")) == null
                                    ? "{}" : textOrNull(legacyCall.get("arguments"))));
                }
            }

            JsonNode usage = root.path("usage");
            Usage tokens = new Usage(
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0)
            );
            return new ChatResult(content, reasoning, calls, finishReason, tokens);
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("Cannot parse DeepSeek response: " + e.getMessage(), 0, e);
        }
    }

    private static String extractErrorCode(String body) {
        String limited = limitBody(body);
        if (limited.isBlank()) {
            return null;
        }
        try {
            JsonNode error = JSON.readTree(limited).path("error");
            for (String field : List.of("code", "type")) {
                JsonNode value = error.path(field);
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (IOException ignored) {
            // Plain-text error body: no machine-readable code.
        }
        return null;
    }

    private static String extractErrorMessage(String body, int status) {
        String message = "DeepSeek API returned HTTP " + status;
        String limited = limitBody(body);
        try {
            JsonNode root = JSON.readTree(limited);
            JsonNode apiMessage = root.path("error").path("message");
            if (apiMessage.isTextual() && !apiMessage.asText().isBlank()) {
                message += ": " + apiMessage.asText();
            }
        } catch (IOException ignored) {
            String trimmed = limited.strip();
            if (!trimmed.isBlank()) {
                message += ": " + truncate(trimmed, 300);
            }
        }
        return message;
    }

    private static String limitBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_LIMIT ? body : body.substring(0, ERROR_BODY_LIMIT);
    }

    private long retryDelayMs(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                if (seconds >= 0 && seconds <= 10) {
                    return seconds * 1000;
                }
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        long base = retryBaseDelay.toMillis() * (1L << (attempt - 1));
        return base + ThreadLocalRandom.current().nextLong(0, 250);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("DeepSeek request interrupted", 0, e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
