package io.github.jasoncai0.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Newline-delimited JSON-RPC over a child process's stdin/stdout. */
final class StdioMcpConnection extends McpConnection {
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    private final ExecutorService reads = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("jade-mcp-stdio-", 0).factory());
    private final StringBuilder stderr = new StringBuilder();
    private volatile boolean closed;

    StdioMcpConnection(McpServerConfig config, Path workingDirectory) {
        super(config);
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command(config.command());
            builder.command().addAll(config.args());
            builder.directory(workingDirectory.toFile());
            builder.environment().putAll(config.env());
            builder.redirectErrorStream(false);
            this.process = builder.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            Thread.ofVirtual().name("jade-mcp-stderr-" + config.name()).start(this::drainStderr);
        } catch (IOException e) {
            throw new McpException("Cannot start MCP server '" + config.name() + "': " + e.getMessage(), e);
        }
    }

    @Override
    protected JsonNode send(JsonNode request) {
        long id = request.path("id").asLong(-1);
        synchronized (this) {
            if (closed) {
                throw new McpException("MCP server '" + config.name() + "' is closed");
            }
            try {
                writer.write(mapper().writeValueAsString(request));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new McpException("Cannot write to MCP server '" + config.name() + "': "
                        + e.getMessage() + stderrSuffix(), e);
            }
            Future<JsonNode> future = reads.submit(() -> readResponse(id));
            try {
                return future.get(config.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                close();
                throw new McpException("MCP server '" + config.name() + "' timed out after "
                        + config.timeoutSeconds() + "s" + stderrSuffix());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException("MCP request interrupted" + stderrSuffix(), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new McpException("MCP server '" + config.name() + "' failed: "
                        + cause.getMessage() + stderrSuffix(), cause);
            }
        }
    }

    @Override
    protected void notify(JsonNode notification) {
        synchronized (this) {
            if (closed) {
                return;
            }
            try {
                writer.write(mapper().writeValueAsString(notification));
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                throw new McpException("Cannot notify MCP server '" + config.name() + "': "
                        + e.getMessage() + stderrSuffix(), e);
            }
        }
    }

    private JsonNode readResponse(long expectedId) throws IOException {
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("MCP server exited" + stderrSuffix());
            }
            if (line.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = mapper().readTree(line);
            } catch (IOException e) {
                // Some servers log to stdout despite the protocol; ignore obvious noise.
                continue;
            }
            if (node.has("id") && node.path("id").asLong(Long.MIN_VALUE) == expectedId) {
                return node;
            }
        }
    }

    private void drainStderr() {
        try (BufferedReader err = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                synchronized (stderr) {
                    stderr.append(line).append('\n');
                    if (stderr.length() > 8_000) {
                        stderr.delete(0, stderr.length() - 8_000);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String stderrSuffix() {
        String value;
        synchronized (stderr) {
            value = stderr.toString().strip();
        }
        if (value.isEmpty()) {
            return "";
        }
        int start = Math.max(0, value.length() - 1_200);
        return "\nstderr: " + value.substring(start);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        reads.shutdownNow();
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
    }
}
