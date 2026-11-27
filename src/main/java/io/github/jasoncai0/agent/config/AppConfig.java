package io.github.jasoncai0.agent.config;

import io.github.jasoncai0.agent.memory.MemoryMode;
import io.github.jasoncai0.agent.memory.MemorySettings;
import io.github.jasoncai0.agent.permission.PermissionMode;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public record AppConfig(
        Path projectRoot,
        Path skillsDir,
        Path workspacesDir,
        LlmSettings llm,
        AgentSettings agent,
        ServerSettings server,
        MysqlSettings mysql,
        MemorySettings memory,
        boolean teamDefault
) {

    /** Backwards-compatible constructor for tests and embedded use. */
    public AppConfig(Path projectRoot, Path skillsDir, Path workspacesDir, LlmSettings llm,
                     AgentSettings agent, ServerSettings server) {
        this(projectRoot, skillsDir, workspacesDir, llm, agent, server, null, null, false);
    }

    /** Backwards-compatible constructor before long-term memory was added. */
    public AppConfig(Path projectRoot, Path skillsDir, Path workspacesDir, LlmSettings llm,
                     AgentSettings agent, ServerSettings server, MysqlSettings mysql) {
        this(projectRoot, skillsDir, workspacesDir, llm, agent, server, mysql, null, false);
    }
    /** Backwards-compatible constructor before team defaults were added. */
    public AppConfig(Path projectRoot, Path skillsDir, Path workspacesDir, LlmSettings llm,
                     AgentSettings agent, ServerSettings server, MysqlSettings mysql, MemorySettings memory) {
        this(projectRoot, skillsDir, workspacesDir, llm, agent, server, mysql, memory, false);
    }

    public static final String VERSION = "1.4.0";

    public static AppConfig load(Path cwd) {
        Path root = cwd.toAbsolutePath().normalize();
        Map<String, String> dotenv = DotEnv.read(root.resolve(".env"));
        Function<String, String> get = key -> {
            String fromEnv = System.getenv(key);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv;
            }
            return dotenv.get(key);
        };

        LlmSettings llm = new LlmSettings(
                value(get, "DEEPSEEK_API_KEY", ""),
                value(get, "DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                value(get, "DEEPSEEK_MODEL", "deepseek-chat"),
                integer(get, "DEEPSEEK_TIMEOUT_SECONDS", 180, 10, 600),
                integer(get, "DEEPSEEK_MAX_OUTPUT_TOKENS", 8192, 256, 32_768)
        );
        AgentSettings agent = new AgentSettings(
                integer(get, "AGENT_MAX_STEPS", 0, 0, AgentSettings.HARD_MAX_STEPS),
                integer(get, "AGENT_MAX_TOOL_CALLS_PER_STEP", 8, 1,
                        AgentSettings.HARD_MAX_TOOL_CALLS_PER_STEP),
                integer(get, "AGENT_CONTEXT_CHAR_BUDGET", 120_000, 20_000, 1_000_000),
                integer(get, "AGENT_COMMAND_TIMEOUT_SECONDS", 120, 1, 600),
                bool(get, "AGENT_AUTO_VERIFY", true),
                PermissionMode.fromId(value(get, "AGENT_PERMISSION_MODE", "balanced")),
                integer(get, "AGENT_TOKEN_BUDGET", (int) AgentSettings.DEFAULT_TOKEN_BUDGET,
                        0, 100_000_000),
                bool(get, "AGENT_GOAL_MODE", true),
                integer(get, "AGENT_CONTEXT_TOKEN_BUDGET", AgentSettings.DEFAULT_CONTEXT_TOKEN_BUDGET,
                        0, 200_000),
                bool(get, "AGENT_OVERFLOW_RECOVERY", AgentSettings.DEFAULT_OVERFLOW_RECOVERY),
                bool(get, "AGENT_SKILL_PRELOAD", AgentSettings.DEFAULT_PRELOAD_SKILLS)
        );
        ServerSettings server = new ServerSettings(
                value(get, "AGENT_HOST", "127.0.0.1"),
                integer(get, "AGENT_PORT", 8080, 0, 65_535),
                value(get, "AGENT_WEB_TOKEN", "")
        );
        MysqlSettings mysql = new MysqlSettings(
                value(get, "JADE_MYSQL_URL", MysqlSettings.DEFAULT_URL),
                value(get, "JADE_MYSQL_USER", "jade"),
                value(get, "JADE_MYSQL_PASSWORD", "jade_dev_password")
        );
        MemorySettings memory = new MemorySettings(
                MemoryMode.fromId(value(get, "AGENT_MEMORY_MODE", "on")),
                value(get, "AGENT_MEMORY_PROVIDER", MemorySettings.DEFAULT_PROVIDER),
                value(get, "AGENT_MEMORY_URL", MemorySettings.DEFAULT_BASE_URL),
                value(get, "AGENT_MEMORY_API_KEY", ""),
                value(get, "AGENT_MEMORY_USER_ID", MemorySettings.DEFAULT_USER_ID),
                integer(get, "AGENT_MEMORY_TOP_K", MemorySettings.DEFAULT_TOP_K, 1, 20),
                integer(get, "AGENT_MEMORY_RECALL_TIMEOUT_MS", MemorySettings.DEFAULT_RECALL_TIMEOUT_MS,
                        50, 10_000),
                integer(get, "AGENT_MEMORY_WRITE_TIMEOUT_MS", MemorySettings.DEFAULT_WRITE_TIMEOUT_MS,
                        100, 30_000),
                bool(get, "AGENT_MEMORY_TELEMETRY", true),
                value(get, "AGENT_MEMORY_PROJECT_ID", "")
        );
        return new AppConfig(
                root,
                path(root, get, "AGENT_SKILLS_DIR", "skills"),
                path(root, get, "AGENT_WORKSPACES_DIR", "agent-workspaces"),
                llm,
                agent,
                server,
                mysql,
                memory,
                bool(get, "AGENT_TEAM_ENABLED", false)
        );
    }

    public boolean hasApiKey() {
        return llm.hasApiKey();
    }

    private static String value(Function<String, String> get, String key, String fallback) {
        String raw = get.apply(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static int integer(Function<String, String> get, String key, int fallback, int min, int max) {
        String raw = get.apply(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got '" + raw.trim() + "'", e);
        }
    }

    private static boolean bool(Function<String, String> get, String key, boolean fallback) {
        String raw = get.apply(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(key + " must be true or false, got '" + raw.trim() + "'");
        };
    }

    private static Path path(Path root, Function<String, String> get, String key, String fallback) {
        Path value = Path.of(value(get, key, fallback));
        return (value.isAbsolute() ? value : root.resolve(value)).toAbsolutePath().normalize();
    }
}
