package io.github.jasoncai0.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal Mem0 OSS REST client. Failures are isolated from the agent loop. */
public final class Mem0HttpMemoryService implements MemoryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_MS = 30_000;

    private final MemorySettings settings;
    private final HttpClient client;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long circuitOpenUntil;
    private volatile MemoryHealth health;

    public Mem0HttpMemoryService(MemorySettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(settings.recallTimeoutMs(), 1_000)))
                .build();
        this.health = new MemoryHealth(false, settings.provider(), settings.mode().id(),
                settings.baseUrl(), "not checked", 0);
    }

    @Override
    public MemoryMode mode() {
        return settings.mode();
    }

    @Override
    public MemoryHealth health() {
        if (circuitOpenUntil > System.currentTimeMillis()) {
            return new MemoryHealth(false, settings.provider(), settings.mode().id(),
                    settings.baseUrl(), "circuit open after repeated failures", 0);
        }
        long start = System.nanoTime();
        try {
            String path = "/memories?user_id=" + enc(settings.userId()) + "&limit=1";
            HttpResponse<String> response = send("GET", path, null, settings.recallTimeout(), false);
            long ms = elapsedMs(start);
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            health = new MemoryHealth(ok, settings.provider(), settings.mode().id(),
                    settings.baseUrl(), ok ? "connected" : "HTTP " + response.statusCode(), ms);
        } catch (RuntimeException e) {
            health = new MemoryHealth(false, settings.provider(), settings.mode().id(),
                    settings.baseUrl(), safeMessage(e), elapsedMs(start));
        }
        return health;
    }

    @Override
    public MemoryScope scope(Path workspace, String sessionId) {
        return MemoryPolicy.scope(settings, workspace, sessionId);
    }

    @Override
    public List<MemoryItem> recall(String query, MemoryScope scope, int limit) {
        String clean = query == null ? "" : query.strip();
        if (clean.isBlank() || limit <= 0) return List.of();
        checkCircuit();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", clean);
        body.put("user_id", scope.userId());
        body.put("agent_id", scope.agentId());
        body.put("limit", Math.min(Math.max(limit, 1), 20));
        try {
            HttpResponse<String> response = send("POST", "/search", body, settings.recallTimeout(), true);
            JsonNode root = read(response);
            JsonNode results = root.isArray() ? root : root.path("results");
            List<MemoryItem> items = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode node : results) {
                    MemoryItem item = item(node);
                    if (item != null) items.add(item);
                }
            }
            success();
            return items;
        } catch (RuntimeException e) {
            failure();
            throw new MemoryException("Mem0 recall failed: " + safeMessage(e), e);
        }
    }

    @Override
    public String remember(MemoryCandidate candidate, MemoryScope scope) {
        checkCircuit();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", candidate.text());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(message));
        body.put("user_id", scope.userId());
        body.put("agent_id", scope.agentId());
        body.put("run_id", scope.sessionId());
        body.put("metadata", MemoryPolicy.metadata(scope, candidate));
        body.put("infer", candidate.infer());
        try {
            HttpResponse<String> response = send("POST", "/memories", body, settings.writeTimeout(), true);
            JsonNode root = read(response);
            JsonNode results = root.path("results");
            success();
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0).path("id").asText("");
            }
            return root.path("id").asText("");
        } catch (RuntimeException e) {
            failure();
            throw new MemoryException("Mem0 write failed: " + safeMessage(e), e);
        }
    }

    @Override
    public List<MemoryItem> list(MemoryScope scope, int limit) {
        checkCircuit();
        try {
            String path = "/memories?user_id=" + enc(scope.userId())
                    + "&agent_id=" + enc(scope.agentId()) + "&limit=" + Math.max(1, limit);
            HttpResponse<String> response = send("GET", path, null, settings.writeTimeout(), true);
            JsonNode root = read(response);
            JsonNode results = root.isArray() ? root : root.path("results");
            List<MemoryItem> items = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode node : results) {
                    MemoryItem item = item(node);
                    if (item != null) items.add(item);
                }
            }
            success();
            return items;
        } catch (RuntimeException e) {
            failure();
            throw new MemoryException("Mem0 list failed: " + safeMessage(e), e);
        }
    }

    @Override
    public String export(MemoryScope scope) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(list(scope, 1_000));
        } catch (JsonProcessingException e) {
            throw new MemoryException("Cannot serialize memories", e);
        }
    }

    @Override
    public void forget(String id) {
        if (id == null || id.isBlank()) return;
        checkCircuit();
        try {
            send("DELETE", "/memories/" + enc(id), null, settings.writeTimeout(), true);
            success();
        } catch (RuntimeException e) {
            failure();
            throw new MemoryException("Mem0 delete failed: " + safeMessage(e), e);
        }
    }

    private MemoryItem item(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String text = node.path("memory").asText(node.path("text").asText(""));
        if (text.isBlank()) return null;
        JsonNode meta = node.path("metadata");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (meta.isObject()) {
            meta.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), value(entry.getValue())));
        }
        String kind = text(metadata.get("kind"), "fact");
        String scope = text(metadata.get("scope"), "project");
        String projectId = text(metadata.get("project_id"), "");
        return new MemoryItem(
                node.path("id").asText(""),
                text,
                node.path("score").asDouble(0.0),
                kind,
                scope,
                projectId,
                node.path("source").asText(text(metadata.get("source"), "mem0")),
                node.path("created_at").asText(""),
                metadata
        );
    }

    private HttpResponse<String> send(String method, String path, Map<String, Object> body,
                                      Duration timeout, boolean authenticated) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(settings.uri(path))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (authenticated && !settings.apiKey().isBlank()) {
                builder.header("X-API-Key", settings.apiKey());
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MemoryException("HTTP " + response.statusCode() + ": " + trim(response.body()));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MemoryException("Mem0 request interrupted", e);
        } catch (IOException e) {
            throw new MemoryException("Mem0 request failed: " + safeMessage(e), e);
        }
    }

    private static JsonNode read(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
        } catch (JsonProcessingException e) {
            throw new MemoryException("Mem0 returned invalid JSON", e);
        }
    }

    private void checkCircuit() {
        if (circuitOpenUntil > System.currentTimeMillis()) {
            throw new MemoryException("Mem0 circuit is open after repeated failures");
        }
    }

    private void success() {
        failures.set(0);
        circuitOpenUntil = 0;
    }

    private void failure() {
        if (failures.incrementAndGet() >= FAILURE_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        return node.toString();
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : trim(message);
    }

    private static String trim(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", " ").strip();
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "…";
    }
}
