package io.github.jasoncai0.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.agent.LiveDeltaBus;
import io.github.jasoncai0.agent.agent.AgentService;
import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.history.EventStore;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local-only HTTP + SSE server. The server exposes no API key and no arbitrary
 * filesystem access; file reads go through the run's Workspace jail.
 */
public final class WebServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final long KEEP_ALIVE_MS = 15_000;
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
        if (!settings.isLoopback() && !settings.hasAuthToken()) {
            throw new IllegalStateException(
                    "Refusing to bind non-loopback host '" + settings.host()
                            + "' without AGENT_WEB_TOKEN configured");
        }
        server = HttpServer.create(new InetSocketAddress(settings.host(), settings.port()), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/", this::handleApi);
        server.createContext("/", this::handleStatic);
        server.start();
        boundPort = server.getAddress().getPort();
        String host = settings.host();
        if (!settings.isLoopback()) {
            Log.warn("web.non_loopback", Map.of("host", host, "tokenProtected", true));
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
            if (!checkRequestGuards(exchange, method)) {
                return;
            }
            route(exchange, method, path);
        } catch (UnsupportedMediaTypeException e) {
            sendError(exchange, 415, e.getMessage());
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
            data.put("sandboxBackend", agentService.config().sandbox().backend());
            if (agentService.config().sandbox().isDocker()) {
                data.put("sandbox", "docker");
                data.put("sandboxActive", true);
                data.put("sandboxNetwork", !"off".equals(agentService.config().sandbox().dockerNetwork()));
                data.put("sandboxDescription", "docker container (" + agentService.config().sandbox().dockerImage()
                        + "; network=" + agentService.config().sandbox().dockerNetwork() + ")");
            } else {
                io.github.jasoncai0.agent.workspace.CommandSandbox sandbox =
                        new io.github.jasoncai0.agent.workspace.CommandRunner().sandbox();
                data.put("sandbox", sandbox.modeName());
                data.put("sandboxActive", sandbox.active());
                data.put("sandboxNetwork", sandbox.networkEnabled());
                data.put("sandboxDescription", sandbox.description());
            }
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
        if (path.equals("/api/mcp") && method.equals("GET")) {
            sendData(exchange, 200, agentService.mcp().summary());
            return;
        }
        if (path.equals("/api/memory/health") && method.equals("GET")) {
            sendData(exchange, 200, agentService.memory().health().toMap());
            return;
        }
        if (path.equals("/api/memory") && method.equals("GET")) {
            io.github.jasoncai0.agent.memory.MemoryScope scope = agentService.memory()
                    .scope(agentService.config().workspacesDir(), "management");
            boolean includeAll = "1".equals(queryParam(exchange.getRequestURI(), "all"));
            long now = System.currentTimeMillis();
            var list = agentService.memory().list(scope, 100).stream()
                    .filter(item -> includeAll || io.github.jasoncai0.agent.memory.MemoryPolicy.isActive(item, now))
                    .toList();
            sendData(exchange, 200, list);
            return;
        }
        if (path.equals("/api/memory/pending") && method.equals("GET")) {
            var pending = agentService.listPendingMemories(100).stream()
                    .map(io.github.jasoncai0.agent.memory.admission.PendingMemoryItem::toMap)
                    .toList();
            sendData(exchange, 200, pending);
            return;
        }
        if (path.startsWith("/api/memory/pending/") && method.equals("POST")) {
            String restPending = path.substring("/api/memory/pending/".length());
            String[] pSegs = restPending.split("/");
            if (pSegs.length == 2 && !pSegs[0].isBlank()) {
                String pendingId = URLDecoder.decode(pSegs[0], StandardCharsets.UTF_8);
                if ("approve".equals(pSegs[1])) {
                    sendData(exchange, 200, agentService.approvePendingMemory(pendingId).toMap());
                    return;
                }
                if ("reject".equals(pSegs[1])) {
                    sendData(exchange, 200, agentService.rejectPendingMemory(pendingId).toMap());
                    return;
                }
            }
            sendError(exchange, 404, "Unknown memory pending endpoint");
            return;
        }
        if (path.equals("/api/memory/export") && method.equals("GET")) {
            io.github.jasoncai0.agent.memory.MemoryScope scope = agentService.memory()
                    .scope(agentService.config().workspacesDir(), "management");
            sendData(exchange, 200, agentService.memory().export(scope));
            return;
        }
        if (path.startsWith("/api/memory/") && method.equals("DELETE")) {
            String id = path.substring("/api/memory/".length());
            agentService.memory().forget(id);
            sendData(exchange, 200, java.util.Map.of("deleted", id));
            return;
        }
        if (path.equals("/api/skills") && method.equals("GET")) {
            sendData(exchange, 200, agentService.skillDescriptions());
            return;
        }
        if (path.equals("/api/runs")) {
            if (method.equals("GET")) {
                var visible = agentService.list().stream()
                        .filter(run -> !run.id().startsWith("team-") && !run.id().startsWith("sub-"))
                        .toList();
                sendData(exchange, 200, visible);
                return;
            }
            if (method.equals("POST")) {
                startRun(exchange);
                return;
            }
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        if (path.equals("/api/runs/prune") && method.equals("POST")) {
            JsonNode body = readJson(exchange);
            int keep = body != null && body.has("keepCount")
                    ? body.path("keepCount").asInt(io.github.jasoncai0.agent.history.SessionArchiver.DEFAULT_KEEP_COUNT)
                    : io.github.jasoncai0.agent.history.SessionArchiver.DEFAULT_KEEP_COUNT;
            int days = body != null && body.has("maxAgeDays")
                    ? body.path("maxAgeDays").asInt(io.github.jasoncai0.agent.history.SessionArchiver.DEFAULT_MAX_AGE_DAYS)
                    : io.github.jasoncai0.agent.history.SessionArchiver.DEFAULT_MAX_AGE_DAYS;
            boolean dryRun = body != null && body.path("dryRun").asBoolean(false);
            sendData(exchange, 200, agentService.pruneSessions(keep, days, dryRun).toMap());
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
        if (action.equals("fork") && method.equals("POST")) {
            forkRun(exchange, id);
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
        if (action.equals("team") && method.equals("GET") && segments.length == 2) {
            sendData(exchange, 200, agentService.teamSummary(id));
            return;
        }
        if (action.equals("team") && method.equals("POST") && segments.length == 2) {
            JsonNode body = readJson(exchange);
            if (body == null || !body.isObject() || !body.has("enabled")) {
                sendError(exchange, 400, "JSON body must contain enabled");
                return;
            }
            sendData(exchange, 200, agentService.setTeamEnabled(id, body.path("enabled").asBoolean(false)));
            return;
        }
        if (action.equals("team") && segments.length > 3 && segments[2].equals("tasks")
                && method.equals("GET")) {
            sendData(exchange, 200, agentService.teamTask(id, segments[3]));
            return;
        }
        if (action.equals("team") && segments.length > 3 && segments[2].equals("permissions")
                && method.equals("POST")) {
            resolveTeamPermission(exchange, id, segments[3]);
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
        if (action.equals("history") && segments.length > 2 && method.equals("GET")) {
            fullEvent(exchange, id, segments[2]);
            return;
        }
        if (action.equals("history") && method.equals("GET")) {
            history(exchange, id);
            return;
        }
        if (action.equals("events") && method.equals("GET")) {
            streamEvents(exchange, id);
            return;
        }
        sendError(exchange, 404, "Unknown run endpoint");
    }

    private void history(HttpExchange exchange, String id) throws IOException {
        long before = longParam(exchange.getRequestURI(), "before", 0);
        int limit = (int) longParam(exchange.getRequestURI(), "limit", 150);
        boolean compact = !"0".equals(queryParam(exchange.getRequestURI(), "compact"));
        EventStore.Page page = agentService.history(id, before, limit, compact);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", page.events());
        data.put("nextBefore", page.nextBefore());
        data.put("hasMore", page.hasMore());
        data.put("lastSeq", page.lastSeq());
        data.put("eventCount", page.eventCount());
        sendData(exchange, 200, data);
    }

    private void fullEvent(HttpExchange exchange, String id, String rawSeq) throws IOException {
        long seq;
        try {
            seq = Long.parseLong(rawSeq);
        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Event sequence must be a number");
            return;
        }
        AgentEvent event = agentService.fullEvent(id, seq).orElse(null);
        if (event == null) {
            sendError(exchange, 404, "Event not found");
            return;
        }
        sendData(exchange, 200, event);
    }

    private long longParam(java.net.URI uri, String name, long fallback) {
        String raw = queryParam(uri, name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Query parameter '" + name + "' must be a number");
        }
    }

    private void startRun(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        if (body == null || !body.isObject()) {
            sendError(exchange, 400, "JSON body must be an object");
            return;
        }
        String task = body.path("task").asText(null);
        String workspace = body.path("workspace").asText(null);
        boolean teamEnabled = body.path("team").asBoolean(false);
        try {
            RunSnapshot snapshot = agentService.start(task, workspace, teamEnabled);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run", snapshot);
            data.put("eventsUrl", "/api/runs/" + snapshot.id() + "/events");
            sendData(exchange, 202, data);
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, e.getMessage());
        }
    }

    private void forkRun(HttpExchange exchange, String id) throws IOException {
        JsonNode body = readJson(exchange);
        String name = body != null && body.isObject() ? body.path("name").asText(null) : null;
        AgentService.ForkResult result = agentService.fork(id, name);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", agentService.require(result.runId()).snapshot());
        data.put("parentRunId", result.parentRunId());
        data.put("eventsUrl", "/api/runs/" + result.runId() + "/events");
        sendData(exchange, 201, data);
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


    private void resolveTeamPermission(HttpExchange exchange, String id, String requestId) throws IOException {
        if (requestId == null || requestId.isBlank()) {
            sendError(exchange, 400, "Permission request id is required");
            return;
        }
        JsonNode body = readJson(exchange);
        if (body == null || !body.isObject()) {
            sendError(exchange, 400, "JSON body must be an object");
            return;
        }
        PermissionDecision decision = "allow".equalsIgnoreCase(body.path("decision").asText("deny"))
                ? PermissionDecision.ALLOW : PermissionDecision.DENY;
        PermissionScope scope = PermissionScope.fromId(body.path("scope").asText("once"));
        boolean resolved = agentService.resolveTeamPermission(id, requestId, decision, scope,
                body.path("reason").asText("lead"));
        if (!resolved) {
            sendError(exchange, 409, "No pending team permission request " + requestId);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId);
        data.put("decision", decision.name());
        data.put("scope", scope.id());
        data.put("team", agentService.teamSummary(id));
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

        // Deltas are not replayed: a reconnect starts from the current head and
        // the durable assistant event will arrive with the final content.
        long lastEventSeq = after;
        long lastDeltaSeq = run.deltas().lastSeq();
        BlockingQueue<Boolean> wakeups = new ArrayBlockingQueue<>(1);
        Runnable wakeup = () -> wakeups.offer(Boolean.TRUE);
        run.events().subscribe(wakeup);
        run.deltas().subscribe(wakeup);
        long lastWrite = System.currentTimeMillis();
        try {
            writeSse(out, "retry: 2000\n\n");
            while (true) {
                long beforeDeltas = lastDeltaSeq;
                lastDeltaSeq = drainDeltas(out, run, lastDeltaSeq, wakeups);
                boolean wrote = lastDeltaSeq != beforeDeltas;
                List<AgentEvent> batch = run.events().after(lastEventSeq);
                for (AgentEvent event : batch) {
                    writeSse(out, formatEvent(event));
                    lastEventSeq = event.seq();
                }
                wrote = wrote || !batch.isEmpty();
                long now = System.currentTimeMillis();
                if (wrote) {
                    lastWrite = now;
                }
                if (run.events().closed()) {
                    writeSse(out, "event: stream_end\ndata: {\"closed\":true}\n\n");
                    break;
                }
                if (now - lastWrite >= KEEP_ALIVE_MS) {
                    writeSse(out, ": keep-alive\n\n");
                    lastWrite = now;
                }
                try {
                    wakeups.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (IOException e) {
            Log.info("web.sse_disconnected", Map.of("runId", id));
        } finally {
            run.events().unsubscribe(wakeup);
            run.deltas().unsubscribe(wakeup);
            try {
                out.close();
            } catch (IOException ignored) {
                // client is gone
            }
        }
    }

    /** Writes pending deltas and returns the newest seq written. */
    private long drainDeltas(OutputStream out, AgentRun run, long lastDeltaSeq,
                             BlockingQueue<Boolean> wakeups) throws IOException {
        List<LiveDeltaBus.Delta> deltas = run.deltas().after(lastDeltaSeq);
        long seq = lastDeltaSeq;
        for (LiveDeltaBus.Delta delta : deltas) {
            writeSse(out, formatDelta(delta));
            seq = delta.seq();
        }
        if (!deltas.isEmpty()) {
            // More may have arrived while writing; do not wait for a fresh signal.
            wakeups.offer(Boolean.TRUE);
        }
        return seq;
    }

    private String formatDelta(LiveDeltaBus.Delta delta) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "assistant.delta");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", delta.text());
        data.put("reasoning", delta.reasoning());
        payload.put("data", data);
        // No SSE id: deltas are not replayable and must not become Last-Event-ID.
        return "event: assistant.delta\ndata: " + mapper.writeValueAsString(payload) + "\n\n";
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
        if (!isAllowedHostHeader(exchange)) {
            sendText(exchange, 403, "text/plain; charset=utf-8", "Forbidden Host header");
            return;
        }
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
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.trim().toLowerCase(Locale.ROOT).startsWith("application/json")) {
                throw new UnsupportedMediaTypeException("Content-Type must be application/json");
            }
            return mapper.readTree(bytes);
        }
    }

    private boolean checkRequestGuards(HttpExchange exchange, String method) throws IOException {
        if (!isAllowedHostHeader(exchange)) {
            sendError(exchange, 403, "Forbidden Host header");
            return false;
        }
        if (settings.hasAuthToken() && !hasValidBearerToken(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendError(exchange, 401, "Missing or invalid bearer token");
            return false;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            String secFetchSite = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            if (secFetchSite != null && secFetchSite.trim().equalsIgnoreCase("cross-site")) {
                sendError(exchange, 403, "Cross-site request blocked");
                return false;
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.isBlank() && !isAllowedOrigin(origin, exchange)) {
                sendError(exchange, 403, "Cross-origin request blocked");
                return false;
            }
        }
        return true;
    }

    private boolean isAllowedHostHeader(HttpExchange exchange) {
        if (!settings.isLoopback()) {
            return true;
        }
        String rawHost = exchange.getRequestHeaders().getFirst("Host");
        if (rawHost == null || rawHost.isBlank()) {
            return true;
        }
        return ServerSettings.isLoopbackHost(extractHostName(rawHost));
    }

    private boolean isAllowedOrigin(String rawOrigin, HttpExchange exchange) {
        String trimmed = rawOrigin.trim();
        if (trimmed.equalsIgnoreCase("null")) {
            return false;
        }
        try {
            URI parsed = URI.create(trimmed);
            String originHost = parsed.getHost();
            if (originHost == null || originHost.isBlank()) {
                return false;
            }
            if (settings.isLoopback()) {
                return ServerSettings.isLoopbackHost(originHost);
            }
            String requestHost = extractHostName(exchange.getRequestHeaders().getFirst("Host"));
            return !requestHost.isBlank() && originHost.equalsIgnoreCase(requestHost);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String extractHostName(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return "";
        }
        String value = hostHeader.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 0 ? value.substring(0, close + 1) : value;
        }
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(0, colon) : value;
    }

    private boolean hasValidBearerToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null) {
            return false;
        }
        String trimmed = auth.trim();
        if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] provided = trimmed.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = settings.authToken().getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(provided, expected);
    }

    private static final class UnsupportedMediaTypeException extends RuntimeException {
        private UnsupportedMediaTypeException(String message) {
            super(message);
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
