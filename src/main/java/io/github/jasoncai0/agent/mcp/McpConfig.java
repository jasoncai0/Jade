package io.github.jasoncai0.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jasoncai0.agent.config.DotEnv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the Claude-compatible {@code mcpServers} JSON format:
 *
 * <pre>
 * {
 *   "mcpServers": {
 *     "github": {
 *       "type": "http",
 *       "url": "https://api.githubcopilot.com/mcp/",
 *       "headers": {"Authorization": "Bearer ${GITHUB_PERSONAL_ACCESS_TOKEN}"}
 *     }
 *   }
 * }
 * </pre>
 *
 * {@code JADE_MCP_CONFIG} overrides the config path. If no file exists, MCP is
 * simply disabled; a broken server declaration does not prevent Jade from
 * starting.
 */
public record McpConfig(List<McpServerConfig> servers) {
    public static final String DEFAULT_FILE = "mcp.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final int MAX_SERVERS = 20;
    private static final int MAX_TOOLS = 200;

    public McpConfig {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    public static McpConfig load(Path projectRoot) {
        Map<String, String> dotenv = DotEnv.read(projectRoot.resolve(".env"));
        String configured = System.getenv("JADE_MCP_CONFIG");
        if (configured == null || configured.isBlank()) {
            configured = dotenv.get("JADE_MCP_CONFIG");
        }
        Path file = configured == null || configured.isBlank()
                ? projectRoot.resolve(DEFAULT_FILE)
                : projectRoot.resolve(configured).normalize();
        if (!Files.isRegularFile(file)) {
            return new McpConfig(List.of());
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            JsonNode entries = root.path("mcpServers");
            if (!entries.isObject()) {
                throw new IllegalArgumentException("mcp.json must contain an object field 'mcpServers'");
            }
            List<McpServerConfig> result = new ArrayList<>();
            var fields = entries.fields();
            while (fields.hasNext() && result.size() < MAX_SERVERS) {
                var entry = fields.next();
                try {
                    result.add(parse(entry.getKey(), entry.getValue(), projectRoot, dotenv));
                } catch (RuntimeException e) {
                    io.github.jasoncai0.agent.config.Log.warn("mcp.config.skip", Map.of(
                            "server", entry.getKey(),
                            "error", String.valueOf(e.getMessage())));
                }
            }
            return new McpConfig(result);
        } catch (IOException | RuntimeException e) {
            io.github.jasoncai0.agent.config.Log.warn("mcp.config.failed", Map.of(
                    "path", file.toString(),
                    "error", String.valueOf(e.getMessage())));
            return new McpConfig(List.of());
        }
    }

    private static McpServerConfig parse(String name, JsonNode node, Path root, Map<String, String> dotenv) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("server must be an object");
        }
        String transport = node.path("type").asText(node.has("command") ? "stdio" : "http");
        String command = expand(node.path("command").asText(""), root, dotenv);
        List<String> args = list(node.path("args"), root, dotenv);
        Map<String, String> env = map(node.path("env"), root, dotenv);
        String url = expand(node.path("url").asText(""), root, dotenv);
        Map<String, String> headers = map(node.path("headers"), root, dotenv);
        int timeout = node.path("timeoutSeconds").asInt(60);
        boolean enabled = node.path("enabled").asBoolean(true);
        return new McpServerConfig(name, transport, command, args, env, url, headers, timeout, enabled);
    }

    private static List<String> list(JsonNode node, Path root, Map<String, String> dotenv) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(expand(item.asText(""), root, dotenv));
        }
        return result;
    }

    private static Map<String, String> map(JsonNode node, Path root, Map<String, String> dotenv) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), expand(entry.getValue().asText(""), root, dotenv)));
        return result;
    }

    private static String expand(String value, Path root, Map<String, String> dotenv) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = System.getenv(key);
            if (replacement == null) {
                replacement = System.getProperty(key);
            }
            if (replacement == null) {
                replacement = dotenv.get(key);
            }
            if (replacement == null && key.equals("PROJECT_ROOT")) {
                replacement = root.toString();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static int maxTools() {
        return MAX_TOOLS;
    }
}
