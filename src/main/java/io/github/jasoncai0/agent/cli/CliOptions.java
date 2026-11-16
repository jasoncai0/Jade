package io.github.jasoncai0.agent.cli;

import io.github.jasoncai0.agent.config.AgentSettings;
import io.github.jasoncai0.agent.permission.PermissionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record CliOptions(
        Mode mode,
        String task,
        String taskFile,
        String workspace,
        Integer maxSteps,
        Integer port,
        String host,
        boolean help,
        boolean version,
        PermissionMode permissionMode,
        boolean dangerouslySkipPermissions
) {
    public enum Mode {
        SERVE, TASK, CHECK, PING
    }

    public static CliOptions parse(String[] args) {
        String task = null;
        String taskFile = null;
        String workspace = null;
        Integer maxSteps = null;
        Integer port = null;
        String host = null;
        boolean help = false;
        boolean version = false;
        boolean check = false;
        boolean ping = false;
        PermissionMode permissionMode = null;
        boolean dangerouslySkipPermissions = false;

        List<String> list = new ArrayList<>(List.of(args));
        for (int i = 0; i < list.size(); i++) {
            String raw = list.get(i);
            String name = raw;
            String inline = null;
            int equals = raw.indexOf('=');
            if (raw.startsWith("--") && equals > 2) {
                name = raw.substring(0, equals);
                inline = raw.substring(equals + 1);
            }
            switch (name) {
                case "--help", "-h" -> help = true;
                case "--version", "-V" -> version = true;
                case "--check" -> check = true;
                case "--ping" -> ping = true;
                case "--task" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    task = value;
                }
                case "--task-file" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    taskFile = value;
                }
                case "--workspace" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    workspace = value;
                }
                case "--max-steps" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    maxSteps = integer(name, value, 0, AgentSettings.HARD_MAX_STEPS);
                }
                case "--port" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    port = integer(name, value, 0, 65_535);
                }
                case "--host" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    host = value;
                }
                case "--permission-mode" -> {
                    String value = inline != null ? inline : requireValue(list, ++i, name);
                    permissionMode = PermissionMode.fromId(value);
                }
                case "--dangerously-skip-permissions" -> dangerouslySkipPermissions = true;
                default -> throw new IllegalArgumentException("Unknown option: " + raw);
            }
        }

        if (help || version) {
            return new CliOptions(Mode.SERVE, task, taskFile, workspace, maxSteps, port, host, help, version,
                    permissionMode, dangerouslySkipPermissions);
        }
        int modes = (check ? 1 : 0) + (ping ? 1 : 0) + (task != null || taskFile != null ? 1 : 0);
        if (modes > 1) {
            throw new IllegalArgumentException("--check, --ping, and --task/--task-file cannot be combined");
        }
        if (task != null && taskFile != null) {
            throw new IllegalArgumentException("Use either --task or --task-file, not both");
        }
        if (task != null && task.isBlank()) {
            throw new IllegalArgumentException("--task must not be blank");
        }
        if (taskFile != null && taskFile.isBlank()) {
            throw new IllegalArgumentException("--task-file must not be blank");
        }
        if (workspace != null) {
            workspace = workspace.strip();
            if (workspace.isEmpty()) {
                throw new IllegalArgumentException("--workspace must not be blank");
            }
        }
        Mode mode = check ? Mode.CHECK : ping ? Mode.PING
                : (task != null || taskFile != null) ? Mode.TASK : Mode.SERVE;
        if (dangerouslySkipPermissions && permissionMode != null) {
            throw new IllegalArgumentException(
                    "--dangerously-skip-permissions cannot be combined with --permission-mode");
        }
        if (dangerouslySkipPermissions) {
            permissionMode = PermissionMode.BYPASS;
        }
        return new CliOptions(mode, task, taskFile, workspace, maxSteps, port, host, false, false,
                permissionMode, dangerouslySkipPermissions);
    }

    private static String requireValue(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        String value = args.get(index);
        if (value.startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return value;
    }

    private static int integer(String option, String raw, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) {
                throw new IllegalArgumentException(option + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be an integer");
        }
    }

    public static String usage() {
        return """
                Jade - a local Java coding agent backed by DeepSeek

                Usage:
                  java -jar target/coding-agent.jar [options]

                  (no options)           start the local web console
                  --task <text>          run one task headlessly
                  --task-file <file>     read the task from a UTF-8 file
                  --workspace <name>     fresh workspace name under agent-workspaces/
                  --max-steps <0-10000>  optional model-turn cap (default 0 = unlimited)
                  --port <0-65535>       web port (default 8080; 0 picks a free port)
                  --host <host>          bind host (default 127.0.0.1)
                  --permission-mode <m>  balanced (default), strict, read-only, bypass
                  --dangerously-skip-permissions  explicit bypass; dangerous
                  --check                validate config, JDK, tools, and skills
                  --ping                 make one small DeepSeek request
                  --version              print the version
                  --help                 print this help

                Environment (a .env file in the current directory is also read):
                  DEEPSEEK_API_KEY       required for real runs
                  DEEPSEEK_BASE_URL      default https://api.deepseek.com
                  DEEPSEEK_MODEL         default deepseek-chat
                  AGENT_AUTO_VERIFY      default true
                  AGENT_MAX_STEPS        default 0 (unlimited)
                  AGENT_TOKEN_BUDGET     default 2000000 (0 = unlimited)
                  AGENT_PERMISSION_MODE  default balanced
                  AGENT_PORT             default 8080

                Examples:
                  cp .env.example .env     # then set DEEPSEEK_API_KEY
                  ./run.sh
                  ./run.sh --task "Create a Java CLI that prints the first 10 primes"
                  ./run.sh --check
                """.stripTrailing();
    }

    public static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
