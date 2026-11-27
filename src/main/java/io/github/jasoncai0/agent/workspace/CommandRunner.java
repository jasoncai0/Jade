package io.github.jasoncai0.agent.workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class CommandRunner {
    private static final int MAX_CAPTURE_CHARS = 200_000;
    private final CommandSandbox sandbox;

    public CommandRunner() {
        this(CommandSandbox.fromEnvironment());
    }

    public CommandRunner(CommandSandbox sandbox) {
        this.sandbox = sandbox == null ? CommandSandbox.fromEnvironment() : sandbox;
    }

    public CommandSandbox sandbox() {
        return sandbox;
    }
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");
    private static final Set<String> SENSITIVE_EXACT = Set.of(
            "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GH_TOKEN", "GITHUB_TOKEN",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "DATABASE_URL", "JDBC_URL", "KUBECONFIG", "JADE_MYSQL_PASSWORD"
    );
    private static final List<Pattern> BLOCKED = List.of(
            Pattern.compile("(?im)(^|[;&|]\\s*)(sudo|su|doas)(\\s|$)"),
            Pattern.compile("(?im)(^|[;&|]\\s*)(shutdown|reboot|halt|poweroff)(\\s|$)"),
            Pattern.compile("(?im)(^|[;&|]\\s*)(mkfs|fdisk|parted)(\\s|$)"),
            Pattern.compile("(?im)\\bdd\\s+if=.*\\bof=/dev/"),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"),
            Pattern.compile("(?im)\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~)(\\s|$)")
    );

    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds) {
        return run(argv, cwd, timeoutSeconds, cwd);
    }

    /**
     * Runs a command with writes confined to {@code writableRoot} when the OS
     * sandbox is active. The original argv is returned for display; the wrapped
     * argv is never copied into the result.
     */
    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds, Path writableRoot) {
        return run(argv, cwd, timeoutSeconds, writableRoot, Map.of());
    }

    /** Runs a command with extra environment variables applied after the sandbox setup. */
    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds,
                             Path writableRoot, Map<String, String> extraEnvironment) {
        return run(argv, cwd, timeoutSeconds, writableRoot, extraEnvironment, false);
    }

    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds,
                             Path writableRoot, Map<String, String> extraEnvironment,
                             boolean allowNetwork) {
        if (argv == null || argv.isEmpty()) {
            throw new CommandRejectedException("Command must not be empty");
        }
        int timeout = Math.max(1, Math.min(timeoutSeconds, 600));
        Path root = writableRoot == null ? cwd : writableRoot;
        ProcessBuilder builder = new ProcessBuilder(sandbox.wrap(argv, root, allowNetwork));
        builder.directory(cwd.toFile());
        sanitizeEnvironment(builder.environment());
        sandbox.prepare(builder, root, allowNetwork);
        if (extraEnvironment != null) {
            extraEnvironment.forEach((key, value) -> {
                if (key != null && value != null) {
                    builder.environment().put(key, value);
                }
            });
        }
        long started = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CommandRejectedException("Cannot start command: " + e.getMessage(), e);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The child may not read stdin.
        }

        AtomicBoolean stdoutTruncated = new AtomicBoolean();
        AtomicBoolean stderrTruncated = new AtomicBoolean();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutThread = Thread.ofVirtual().start(
                () -> collect(process.getInputStream(), stdout, stdoutTruncated));
        Thread stderrThread = Thread.ofVirtual().start(
                () -> collect(process.getErrorStream(), stderr, stderrTruncated));

        boolean timedOut = false;
        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                timedOut = true;
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            stdoutThread.join(Duration.ofSeconds(2));
            stderrThread.join(Duration.ofSeconds(2));
            int exit = timedOut ? 124 : process.exitValue();
            String out = stdout.toString();
            String err = stderr.toString();
            if (stdoutTruncated.get()) {
                out += "\n... [stdout truncated]";
            }
            if (stderrTruncated.get()) {
                err += "\n... [stderr truncated]";
            }
            return new CommandResult(
                    List.copyOf(argv),
                    exit,
                    out,
                    err,
                    timedOut,
                    (System.nanoTime() - started) / 1_000_000
            );
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CommandRejectedException("Command interrupted", e);
        }
    }

    public CommandResult runShell(String command, Path cwd, int timeoutSeconds) {
        return runShell(command, cwd, timeoutSeconds, cwd);
    }

    public CommandResult runShell(String command, Path cwd, int timeoutSeconds, Path writableRoot) {
        return runShell(command, cwd, timeoutSeconds, writableRoot, false);
    }

    public CommandResult runShell(String command, Path cwd, int timeoutSeconds, Path writableRoot,
                                  boolean allowNetwork) {
        if (command == null || command.isBlank()) {
            throw new CommandRejectedException("Command must not be blank");
        }
        guard(command);
        List<String> argv = WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
        return run(argv, cwd, timeoutSeconds, writableRoot, Map.of(), allowNetwork);
    }

    public static void guard(String command) {
        for (Pattern pattern : BLOCKED) {
            if (pattern.matcher(command).find()) {
                throw new CommandRejectedException(
                        "Command blocked by the demo's destructive-command guard: " + pattern.pattern());
            }
        }
    }

    private static void sanitizeEnvironment(Map<String, String> env) {
        List<String> remove = new ArrayList<>();
        for (String key : env.keySet()) {
            if (SENSITIVE_EXACT.contains(key)
                    || key.endsWith("_API_KEY")
                    || key.endsWith("_SECRET")
                    || key.endsWith("_TOKEN")
                    || key.endsWith("_PASSWORD")
                    || key.endsWith("_PASSWD")
                    || key.endsWith("_PRIVATE_KEY")) {
                remove.add(key);
            }
        }
        remove.forEach(env::remove);
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        env.put("GIT_PAGER", "cat");
        env.put("PAGER", "cat");
        env.put("CI", "true");
        env.put("AGENT_SANDBOX_WORKSPACE", "1");
    }

    private static void collect(InputStream stream, StringBuilder target, AtomicBoolean truncated) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (target.length() < MAX_CAPTURE_CHARS) {
                    int remaining = MAX_CAPTURE_CHARS - target.length();
                    target.append(buffer, 0, Math.min(read, remaining));
                    if (read > remaining) {
                        truncated.set(true);
                    }
                } else {
                    truncated.set(true);
                }
            }
        } catch (IOException ignored) {
            // The process was killed or the pipe closed; output collected so far is still useful.
        }
    }

    public record CommandResult(
            List<String> argv,
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            long durationMs
    ) {
        public String commandLine() {
            return String.join(" ", argv);
        }

        public boolean success() {
            return !timedOut && exitCode == 0;
        }

        public String combinedOutput(int maxChars) {
            StringBuilder out = new StringBuilder();
            if (!stdout.isBlank()) {
                out.append(stdout.stripTrailing());
            }
            if (!stderr.isBlank()) {
                if (!out.isEmpty()) {
                    out.append("\n--- stderr ---\n");
                }
                out.append(stderr.stripTrailing());
            }
            if (out.isEmpty()) {
                out.append("(no output)");
            }
            String value = out.toString();
            if (value.length() > maxChars) {
                return value.substring(0, maxChars) + "\n... [output truncated]";
            }
            return value;
        }
    }

    public static final class CommandRejectedException extends RuntimeException {
        public CommandRejectedException(String message) {
            super(message);
        }

        public CommandRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
