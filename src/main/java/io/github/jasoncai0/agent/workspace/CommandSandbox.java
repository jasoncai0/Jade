package io.github.jasoncai0.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OS-level confinement for tool-started commands.
 *
 * <p>On macOS this wraps commands with {@code sandbox-exec} and a write jail:
 * reads stay broad so compilers and runtimes work, writes are limited to the
 * workspace and its private temp directory, and network access is denied unless
 * explicitly enabled. The profile is deliberately not presented as a container
 * or a VM: absolute-path reads and kernel attack surface are still shared with
 * the host.</p>
 */
public final class CommandSandbox {
    public enum Mode {
        AUTO, OFF, REQUIRED
    }

    private static final Path MACOS_SANDBOX = Path.of("/usr/bin/sandbox-exec");
    private static final String UNIX_PROFILE = """
            (version 1)
            (deny default)
            (allow process*)
            (allow file-read*)
            (allow file-write* (subpath "%s") (subpath "%s") (literal "/dev/null"))
            (allow sysctl-read)
            (allow mach-lookup)
            %s
            """;

    private final Mode mode;
    private final boolean network;
    private final Path executable;
    private final boolean active;

    private CommandSandbox(Mode mode, boolean network, Path executable, boolean active) {
        this.mode = mode;
        this.network = network;
        this.executable = executable;
        this.active = active;
    }

    public static CommandSandbox fromEnvironment() {
        Mode mode = parseMode(System.getenv("AGENT_SANDBOX"));
        boolean network = parseBoolean(System.getenv("AGENT_SANDBOX_NETWORK"), false);
        return create(mode, network, MACOS_SANDBOX);
    }

    static CommandSandbox create(Mode mode, boolean network, Path executable) {
        boolean available = executable != null && Files.isExecutable(executable);
        if (mode == Mode.REQUIRED && !available) {
            throw new IllegalStateException(
                    "AGENT_SANDBOX=required but sandbox-exec is not available at " + executable);
        }
        return new CommandSandbox(mode, network, executable, mode != Mode.OFF && available);
    }

    public boolean active() {
        return active;
    }

    public boolean networkEnabled() {
        return network;
    }

    public String modeName() {
        if (active) {
            return "workspace-write";
        }
        return switch (mode) {
            case OFF -> "off";
            case REQUIRED -> "required-unavailable";
            case AUTO -> "unavailable";
        };
    }

    public String description() {
        if (active) {
            return "macOS sandbox-exec write jail; network=" + (network ? "enabled" : "denied");
        }
        return "disabled (" + modeName() + "; set AGENT_SANDBOX=off|auto|required)";
    }

    /**
     * Creates the workspace-private temp directories and sets up environment
     * variables that keep compiler caches and temp files inside the jail.
     */
    public void prepare(ProcessBuilder builder, Path writableRoot) {
        Path root = writableRoot.toAbsolutePath().normalize();
        Path tmp = root.resolve(".agent-build/tmp");
        Path goCache = root.resolve(".agent-build/go-cache");
        try {
            Files.createDirectories(tmp);
            Files.createDirectories(goCache);
            root = root.toRealPath();
            tmp = tmp.toRealPath();
        } catch (IOException e) {
            throw new CommandRunner.CommandRejectedException(
                    "Cannot create sandbox temp directory: " + e.getMessage(), e);
        }
        Map<String, String> env = builder.environment();
        env.put("TMPDIR", tmp.toString());
        env.put("TMP", tmp.toString());
        env.put("TEMP", tmp.toString());
        env.put("GOCACHE", goCache.toString());
        env.put("GOTMPDIR", tmp.toString());
        env.put("AGENT_SANDBOX_ACTIVE", active ? "1" : "0");
        env.put("AGENT_SANDBOX_NETWORK", network ? "1" : "0");
    }

    /**
     * Wraps the argv only when confinement is active. The caller keeps the
     * original argv for display and logging.
     */
    public List<String> wrap(List<String> argv, Path writableRoot) {
        if (!active) {
            return argv;
        }
        Path root = writableRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(root.resolve(".agent-build/tmp"));
            root = root.toRealPath();
        } catch (IOException e) {
            throw new CommandRunner.CommandRejectedException(
                    "Cannot resolve sandbox workspace: " + e.getMessage(), e);
        }
        Path tmp = root.resolve(".agent-build/tmp");
        String profile = UNIX_PROFILE.formatted(
                escape(root.toString()),
                escape(tmp.toString()),
                network ? "(allow network*)" : "");
        List<String> wrapped = new ArrayList<>(argv.size() + 5);
        wrapped.add(executable.toString());
        wrapped.add("-p");
        wrapped.add(profile);
        wrapped.add("--");
        wrapped.addAll(argv);
        return wrapped;
    }

    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Mode.AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off", "false", "none" -> Mode.OFF;
            case "auto", "on", "true" -> Mode.AUTO;
            case "required", "strict" -> Mode.REQUIRED;
            default -> throw new IllegalArgumentException(
                    "AGENT_SANDBOX must be off, auto, or required, got '" + raw.trim() + "'");
        };
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException(
                    "AGENT_SANDBOX_NETWORK must be true or false, got '" + raw.trim() + "'");
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
