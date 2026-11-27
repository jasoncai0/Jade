package io.github.jasoncai0.agent.workspace;

import io.github.jasoncai0.agent.sandbox.LocalSandboxSession;
import io.github.jasoncai0.agent.sandbox.SandboxInfo;
import io.github.jasoncai0.agent.sandbox.SandboxSession;

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

    private final CommandSandbox sandbox;
    private final SandboxSession session;

    public CommandRunner() {
        this(CommandSandbox.fromEnvironment());
    }

    public CommandRunner(CommandSandbox sandbox) {
        CommandSandbox safe = sandbox == null ? CommandSandbox.fromEnvironment() : sandbox;
        this.sandbox = safe;
        this.session = new LocalSandboxSession(safe);
    }

    public CommandRunner(SandboxSession session) {
        if (session == null) {
            CommandSandbox safe = CommandSandbox.fromEnvironment();
            this.sandbox = safe;
            this.session = new LocalSandboxSession(safe);
        } else if (session instanceof LocalSandboxSession local) {
            this.sandbox = local.commandSandbox();
            this.session = local;
        } else {
            this.sandbox = CommandSandbox.off();
            this.session = session;
        }
    }

    public CommandSandbox sandbox() {
        return sandbox;
    }

    public SandboxSession session() {
        return session;
    }

    public SandboxInfo sandboxInfo() {
        return session.info();
    }

    public boolean isContainer() {
        return session.info().containerIsolated();
    }

    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds) {
        return run(argv, cwd, timeoutSeconds, cwd);
    }

    /**
     * Runs a command confined to the active {@link SandboxSession} (local write jail
     * by default, or hardened Docker container when {@code AGENT_SANDBOX_BACKEND=docker}).
     */
    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds, Path writableRoot) {
        return run(argv, cwd, timeoutSeconds, writableRoot, Map.of());
    }

    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds,
                             Path writableRoot, Map<String, String> extraEnvironment) {
        return run(argv, cwd, timeoutSeconds, writableRoot, extraEnvironment, false);
    }

    public CommandResult run(List<String> argv, Path cwd, int timeoutSeconds,
                             Path writableRoot, Map<String, String> extraEnvironment,
                             boolean allowNetwork) {
        return session.exec(argv, cwd, timeoutSeconds, writableRoot, extraEnvironment, allowNetwork);
    }

    public CommandResult runShell(String command, Path cwd, int timeoutSeconds) {
        return runShell(command, cwd, timeoutSeconds, cwd);
    }

    public CommandResult runShell(String command, Path cwd, int timeoutSeconds, Path writableRoot) {
        return runShell(command, cwd, timeoutSeconds, writableRoot, false);
    }

    public CommandResult runShell(String command, Path cwd, int timeoutSeconds, Path writableRoot,
                                  boolean allowNetwork) {
        return session.execShell(command, cwd, timeoutSeconds, writableRoot, allowNetwork);
    }

    /** Shared low-level host process launcher used by {@link LocalSandboxSession} and Docker CLI invocations. */
    public static CommandResult execLocalProcess(CommandSandbox sandbox, List<String> argv, Path cwd,
                                                 int timeoutSeconds, Path writableRoot,
                                                 Map<String, String> extraEnvironment,
                                                 boolean allowNetwork) {
        if (argv == null || argv.isEmpty()) {
            throw new CommandRejectedException("Command must not be empty");
        }
        int timeout = Math.max(1, Math.min(timeoutSeconds, 600));
        Path root = writableRoot == null ? cwd : writableRoot;
        CommandSandbox effectiveSandbox = sandbox == null ? CommandSandbox.off() : sandbox;
        ProcessBuilder builder = new ProcessBuilder(effectiveSandbox.wrap(argv, root, allowNetwork));
        builder.directory(cwd.toFile());
        sanitizeEnvironment(builder.environment());
        if (effectiveSandbox.active() || !allowNetwork) {
            effectiveSandbox.prepare(builder, root, allowNetwork);
        }
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

    public static void guard(String command) {
        for (Pattern pattern : BLOCKED) {
            if (pattern.matcher(command).find()) {
                throw new CommandRejectedException(
                        "Command blocked by the demo's destructive-command guard: " + pattern.pattern());
            }
        }
    }

    public static boolean isSensitiveEnvName(String key) {
        if (key == null) return false;
        return SENSITIVE_EXACT.contains(key)
                || key.endsWith("_API_KEY")
                || key.endsWith("_SECRET")
                || key.endsWith("_TOKEN")
                || key.endsWith("_PASSWORD")
                || key.endsWith("_PASSWD")
                || key.endsWith("_PRIVATE_KEY");
    }

    private static void sanitizeEnvironment(Map<String, String> env) {
        List<String> remove = new ArrayList<>();
        for (String key : env.keySet()) {
            if (isSensitiveEnvName(key)) {
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
