package io.github.jasoncai0.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * MCP Streamable HTTP transport. Each JSON-RPC message is one POST; a server
 * may answer with JSON or with an SSE stream whose data frames contain the
 * response. The session header is retained for subsequent requests.
 */
final class HttpMcpConnection extends McpConnection {
    private final HttpClient http;
    private final URI uri;
    private final Duration timeout;
    private volatile String sessionId;
    private volatile boolean closed;

    HttpMcpConnection(McpServerConfig config) {
        super(config);
        try {
            this.uri = URI.create(config.url());
        } catch (RuntimeException e) {
            throw new McpException("Invalid MCP URL for '" + config.name() + "': " + config.url(), e);
        }
        this.timeout = Duration.ofSeconds(config.timeoutSeconds());
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected JsonNode send(JsonNode request) {
        long id = request.path("id").asLong(-1);
        HttpResponse<InputStream> response = post(request);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new McpException("MCP HTTP " + config.name() + " returned HTTP " + status
                    + ": " + bodySnippet(response.body()));
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        try (InputStream body = response.body()) {
            if (contentType.toLowerCase().contains("text/event-stream")) {
                return readSse(body, id);
            }
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) {
                throw new McpException("MCP HTTP " + config.name() + " returned an empty response");
            }
            JsonNode node = mapper().readTree(text);
            return node;
        } catch (IOException e) {
            throw new McpException("Cannot read MCP HTTP response from '" + config.name() + "': "
                    + e.getMessage(), e);
        }
    }

    @Override
    protected void notify(JsonNode notification) {
        if (closed) {
            return;
        }
        HttpResponse<InputStream> response = post(notification);
        try (InputStream body = response.body()) {
            body.readAllBytes();
        } catch (IOException ignored) {
        }
        if (response.statusCode() >= 400) {
            throw new McpException("MCP HTTP notification to '" + config.name()
                    + "' returned HTTP " + response.statusCode());
        }
    }

    private HttpResponse<InputStream> post(JsonNode message) {
        if (closed) {
            throw new McpException("MCP server '" + config.name() + "' is closed");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper().writeValueAsString(message)));
            config.headers().forEach(builder::header);
            String session = sessionId;
            if (session != null && !session.isBlank()) {
                builder.header("Mcp-Session-Id", session);
            }
            if (protocolVersion != null && !protocolVersion.isBlank()) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            HttpResponse<InputStream> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            return response;
        } catch (IOException e) {
            throw new McpException("MCP HTTP request to '" + config.name() + "' failed: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP HTTP request interrupted", e);
        }
    }

    private JsonNode readSse(InputStream body, long expectedId) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).strip();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    continue;
                }
                JsonNode node = mapper().readTree(data);
                if (node.has("id") && node.path("id").asLong(Long.MIN_VALUE) == expectedId) {
                    return node;
                }
            }
        }
        throw new McpException("MCP HTTP " + config.name() + " closed the SSE stream without response " + expectedId);
    }

    private String bodySnippet(InputStream body) {
        try (InputStream input = body) {
            String text = new String(input.readNBytes(2_000), StandardCharsets.UTF_8);
            return text.isBlank() ? "(empty)" : text;
        } catch (IOException e) {
            return "(unreadable body)";
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
