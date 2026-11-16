package io.github.jasoncai0.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.agent.RunSnapshot;
import io.github.jasoncai0.agent.permission.PermissionDecision;
import io.github.jasoncai0.agent.permission.PermissionRequest;
import io.github.jasoncai0.agent.permission.PermissionResult;
import io.github.jasoncai0.agent.permission.PermissionScope;
import io.github.jasoncai0.agent.question.QuestionAnswer;
import io.github.jasoncai0.agent.question.QuestionRequest;
import io.github.jasoncai0.agent.config.AppConfig;
import io.github.jasoncai0.agent.config.Log;
import io.github.jasoncai0.agent.config.ServerSettings;
import io.github.jasoncai0.agent.workspace.Workspace;
import io.github.jasoncai0.agent.workspace.WorkspaceException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local-only HTTP + SSE server. The server exposes no API key and no arbitrary
 * filesystem access; file reads go through the run's Workspace jail.
 */
public final class WebServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentService agentService;
    private final ServerSettings settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private int boundPort;

    public WebServer(AgentService agentService, ServerSettings settings) {
        this.agentService = agentService;
        this.settings = settings;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.start();
        boundPort = server.getAddress().getPort();
        String host = settings.host();
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
            Log.warn("web.non_loopback", Map.of("host", host));
        }
        Log.info("web.started", Map.of("url", url(), "boundPort", boundPort));
    }

    public int port() {
        return boundPort;
    }

    public String url() {
        String host = settings.host();
        if (host.equals("0.0.0.0") || host.equals("::")) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + boundPort;
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        long started = System.currentTimeMillis();
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            Log.info("web.request", Map.of("method", method, "path", path));
            route(exchange, method, path);
        } catch (NoSuchElementException e) {
            sendError(exchange, 404, e.getMessage() == null ? "Not found" : e.getMessage());
        } catch (IllegalArgumentException | WorkspaceException e) {
            sendError(exchange, 400, e.getMessage() == null ? "Bad request" : e.getMessage());
        } catch (IllegalStateException e) {
            sendError(exchange, 409, e.getMessage() == null ? "Session is busy" : e.getMessage());
        } catch (Exception e) {
            Log.error("web.request_failed", e, Map.of("path", exchange.getRequestURI().getPath()));
            if (!headersSent(exchange)) {
                sendError(exchange, 500, "Internal server error: " + e.getMessage());
            }
        } finally {
            Log.info("web.response", Map.of(
                    "method", exchange.getRequestMethod(),
                    "path", exchange.getRequestURI().getPath(),
                    "status", exchange.getResponseCode(),
                    "durationMs", System.currentTimeMillis() - started
            ));
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String method, String path) throws IOException {
        if (path.equals("/api/health") && method.equals("GET")) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "ok");
            data.put("version", AppConfig.VERSION);
            data.put("model", agentService.config().llm().model());
            data.put("apiKeyConfigured", agentService.config().hasApiKey());
            data.put("maxSteps", agentService.config().agent().maxSteps());
            data.put("runs", agentService.list().size());
            sendData(exchange, 200, data);
            return;
        }
        if (path.equals("/api/config") && method.equals("GET")) {
            sendData(exchange, 200, agentService.configSummary());
            return;
        }
        if (path.equals("/api/tools") && method.equals("GET")) {
            sendData(exchange, 200, agentService.toolDescriptions());
            return;
        }
        if (path.equals("/api/skills") && method.equals("GET")) {
            sendData(exchange, 200, agentService.skillDescriptions());
            return;
        }
        if (path.equals("/api/runs")) {
            if (method.equals("GET")) {
                sendData(exchange, 200, agentService.list());
                return;
            }
            if (method.equals("POST")) {
                startRun(exchange);
                return;
            }
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        if (!path.startsWith("/api/runs/")) {
            sendError(exchange, 404, "Unknown API endpoint");
            return;
        }

        String rest = path.substring("/api/runs/".length());
        String[] segments = rest.split("/");
        String id = segments.length == 0 ? "" : segments[0];
        if (id.isBlank()) {
            sendError(exchange, 404, "Unknown API endpoint");
            return;
        }
        String action = segments.length > 1 ? segments[1] : "";

        if (action.isEmpty() && method.equals("GET")) {
            sendData(exchange, 200, agentService.require(id).snapshot());
            return;
        }
        if (action.equals("cancel") && method.equals("POST")) {
            sendData(exchange, 200, agentService.cancel(id));
            return;
        }
        if (action.equals("messages") && method.equals("POST")) {
            followUp(exchange, id);
            return;
        }
        if (action.equals("permissions") && method.equals("GET")) {
            PermissionRequest pending = agentService.pendingPermission(id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pending", pending == null ? List.of() : List.of(pending.publicView()));
            sendData(exchange, 200, data);
            return;
        }
        if (action.equals("permissions") && method.equals("POST")) {
            resolvePermission(exchange, id, segments.length > 2 ? segments[2] : null);
            return;
        }
        if (action.equals("questions") && method.equals("GET")) {
            QuestionRequest pending = agentService.pendingQuestion(id);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pending", pending == null ? List.of() : List.of(pending.publicView()));
            sendData(exchange, 200, data);
            return;
        }
        if (action.equals("questions") && method.equals("POST")) {
            resolveQuestion(exchange, id, segments.length > 2 ? segments[2] : null);
            return;
        }
        if (action.equals("files") && method.equals("GET")) {
            AgentRun run = agentService.require(id);
            List<Map<String, Object>> files = new ArrayList<>();
            for (Workspace.FileEntry entry : run.workspace().listFiles(1000, 12)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", entry.path());
                item.put("size", entry.size());
                item.put("directory", entry.directory());
                files.add(item);
            }
            sendData(exchange, 200, files);
            return;
        }
        if (action.equals("file") && method.equals("GET")) {
            String file = queryParam(exchange.getRequestURI(), "path");
            if (file == null || file.isBlank()) {
                sendError(exchange, 400, "Query parameter 'path' is required");
                return;
            }
            String content = agentService.readFile(id, file);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", file);
            data.put("content", content);
            sendData(exchange, 200, data);
            return;
        }
        if (action.equals("events") && method.equals("GET")) {
            streamEvents(exchange, id);
            return;
        }
        sendError(exchange, 404, "Unknown run endpoint");
    }

    private void startRun(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        if (body == null || !body.isObject()) {
            sendError(exchange, 400, "JSON body must be an object");
            return;
        }
        String task = body.path("task").asText(null);
        String workspace = body.path("workspace").asText(null);
        try {
            RunSnapshot snapshot = agentService.start(task, workspace);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run", snapshot);
            data.put("eventsUrl", "/api/runs/" + snapshot.id() + "/events");
            sendData(exchange, 202, data);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        }
    }

    private void followUp(HttpExchange exchange, String id) throws IOException {
        JsonNode body = readJson(exchange);
        if (body == null || !body.isObject()) {
            sendError(exchange, 400, "JSON body must be an object");
            return;
        }
        String message = body.path("message").asText(null);
        RunSnapshot snapshot = agentService.followUp(id, message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", snapshot);
        data.put("eventsUrl", "/api/runs/" + snapshot.id() + "/events");
        sendData(exchange, 202, data);
    }

    private void resolvePermission(HttpExchange exchange, String id, String requestId) throws IOException {
        if (requestId == null || requestId.isBlank()) {
            sendError(exchange, 400, "Permission request id is required");
            return;
        }
        JsonNode body = readJson(exchange);
        if (body == null || !body.isObject()) {
            sendError(exchange, 400, "JSON body must be an object");
            return;
        }
        String rawDecision = body.path("decision").asText("deny");
        PermissionDecision decision;
        try {
            decision = switch (rawDecision.toLowerCase()) {
                case "allow" -> PermissionDecision.ALLOW;
                case "deny" -> PermissionDecision.DENY;
                default -> throw new IllegalArgumentException("decision must be allow or deny");
            };
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
            return;
        }
        PermissionScope scope = PermissionScope.fromId(body.path("scope").asText("once"));
        String reason = body.path("reason").asText("web");
        PermissionResult result = agentService.resolvePermission(id, requestId, decision, scope, reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("decision", decision.name());
        data.put("scope", scope.id());
        data.put("networkAllowed", result.networkAllowed());
        data.put("run", agentService.require(id).snapshot());
        sendData(exchange, 200, data);
    }


    private void resolveQuestion(HttpExchange exchange, String id, String requestId) throws IOException {
        if (requestId == null || requestId.isBlank()) {
            sendError(exchange, 400, "Question request id is required");
            return;
        }
        JsonNode body = readJson(exchange);
        if (body == null || !body.isObject()) {
            sendError(exchange, 400, "JSON body must be an object");
            return;
        }
        List<String> selected = new ArrayList<>();
        JsonNode selectedNode = body.path("selected");
        if (selectedNode.isArray()) {
            for (JsonNode item : selectedNode) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    selected.add(item.asText());
                }
            }
        }
        String text = body.path("text").asText("");
        QuestionAnswer answer = agentService.resolveQuestion(id, requestId, selected, text);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("selected", answer.selected());
        data.put("text", answer.text());
        data.put("run", agentService.require(id).snapshot());
        sendData(exchange, 200, data);
    }

    private void streamEvents(HttpExchange exchange, String id) throws IOException {
        AgentRun run = agentService.require(id);
        long after = parseLong(exchange.getRequestHeaders().getFirst("Last-Event-ID"),
                queryLong(exchange.getRequestURI(), "after", 0));

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            writeSse(out, "retry: 2000\n\n");
            while (true) {
                List<AgentEvent> batch = run.events().after(after);
                if (!batch.isEmpty()) {
                    for (AgentEvent event : batch) {
                        writeSse(out, formatEvent(event));
                        after = event.seq();
                    }
                    continue;
                }
                if (run.events().closed()) {
                    writeSse(out, "event: stream_end\ndata: {\"closed\":true}\n\n");
                    break;
                }
                AgentEvent next;
                try {
                    next = run.events().awaitAfter(after, 15_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (next == null) {
                    writeSse(out, ": keep-alive\n\n");
                }
            }
        } catch (IOException e) {
            Log.info("web.sse_disconnected", Map.of("runId", id));
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // client is gone
            }
        }
    }

    private String formatEvent(AgentEvent event) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", event.seq());
        payload.put("timestamp", event.timestamp());
        payload.put("type", event.type());
        payload.put("data", event.data());
        return "id: " + event.seq() + "\n"
                + "event: " + event.type() + "\n"
                + "data: " + mapper.writeValueAsString(payload) + "\n\n";
    }

    private static void writeSse(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }
        if (!path.equals("/index.html") && !path.equals("/app.js")
                && !path.equals("/styles.css") && !path.equals("/favicon.svg")) {
            sendText(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        byte[] bytes;
        try (InputStream in = WebServer.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                sendText(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            bytes = in.readAllBytes();
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(path));
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; connect-src 'self'; font-src 'self'; "
                        + "frame-ancestors 'none'; base-uri 'none'");
        if (method.equals("HEAD")) {
            headers.set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private void sendData(HttpExchange exchange, int status, Object data) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("code", 0);
        root.put("message", "ok");
        root.set("data", mapper.valueToTree(data));
        root.put("traceId", traceId());
        byte[] bytes = mapper.writeValueAsBytes(root);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("code", status);
        root.put("message", message == null ? "error" : message);
        root.putNull("data");
        root.put("traceId", traceId());
        byte[] bytes = mapper.writeValueAsBytes(root);
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json; charset=utf-8");
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private void sendText(HttpExchange exchange, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("Request body is too large");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Content-Length");
            }
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Request body is too large");
            }
            if (bytes.length == 0) {
                return null;
            }
            return mapper.readTree(bytes);
        }
    }

    private static String queryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String rawKey = equals < 0 ? pair : pair.substring(0, equals);
            if (!URLDecoder.decode(rawKey, StandardCharsets.UTF_8).equals(key)) {
                continue;
            }
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        }
        return null;
    }

    private static long queryLong(URI uri, String key, long fallback) {
        String raw = queryParam(uri, key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String contentType(String path) {
        return switch (path.substring(path.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    private static boolean headersSent(HttpExchange exchange) {
        try {
            return exchange.getResponseCode() != -1;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String traceId() {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Log.info("web.stopped", Map.of());
    }
}
