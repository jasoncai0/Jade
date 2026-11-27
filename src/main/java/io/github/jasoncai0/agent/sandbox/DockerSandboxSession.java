package io.github.jasoncai0.agent.sandbox;

import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.CommandSandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardened per-session Docker container execution boundary (docs/SANDBOX.md §6.2):
 * --read-only rootfs, --tmpfs /tmp:rw,noexec,nosuid,size=256m, --cap-drop=ALL,
 * --security-opt=no-new-privileges, --pids-limit, --memory, --cpus, --user 10001:10001,
 * --network none (when off), and workspace bind-mounted at /workspace:rw.
 * Fail-closed: never falls back to host execution on failure.
 */
public final class DockerSandboxSession implements SandboxSession {
    public static final String CONTAINER_WORKSPACE = "/workspace";
    private static final CommandSandbox NO_HOST_JAIL = CommandSandbox.off();

    private final SandboxSpec spec;
    private final String containerName;
    private final String dockerBin;
    private volatile boolean started;

    public DockerSandboxSession(SandboxSpec spec) {
        this(spec, "docker");
    }

    public DockerSandboxSession(SandboxSpec spec, String dockerBin) {
        this.spec = spec;
        this.dockerBin = dockerBin == null || dockerBin.isBlank() ? "docker" : dockerBin.trim();
        this.containerName = buildContainerName(spec.sessionId());
    }

    public String containerName() {
        return containerName;
    }

    static String buildContainerName(String sessionId) {
        String clean = (sessionId == null ? "session" : sessionId)
                .replaceAll("[^A-Za-z0-9_.-]", "-")
                .replaceAll("^-+", "");
        if (clean.isEmpty()) {
            clean = "session";
        }
        if (clean.length() > 48) {
            clean = clean.substring(0, 48);
        }
        return "jade-" + clean;
    }

    @Override
    public synchronized void start() {
        if (started && isContainerRunning()) {
            return;
        }
        Path wsRoot = prepareWorkspaceDirs(spec.workspaceRoot());
        // Clean up any stale exited container with the same session name
        runHostDocker(List.of(dockerBin, "rm", "-f", containerName), 10);

        List<String> cmd = buildDockerRunCommand(wsRoot);
        CommandRunner.CommandResult res = runHostDocker(cmd, 30);
        if (!res.success()) {
            throw new CommandRunner.CommandRejectedException(
                    "Docker sandbox failed to start container '" + containerName
                            + "' (image=" + spec.settings().dockerImage() + "): "
                            + res.combinedOutput(2_000)
                            + " — build the image with `./scripts/build-sandbox-image.sh` or set AGENT_SANDBOX_DOCKER_IMAGE.");
        }
        started = true;
    }

    List<String> buildDockerRunCommand(Path realWorkspaceRoot) {
        SandboxSettings s = spec.settings();
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBin);
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--label");
        cmd.add("jade.session=" + spec.sessionId());
        cmd.add("--init");
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,noexec,nosuid,size=256m");
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("--pids-limit=" + s.dockerPids());
        cmd.add("--memory=" + s.dockerMemory());
        cmd.add("--cpus=" + s.dockerCpus());
        cmd.add("--user");
        cmd.add("10001:10001");
        cmd.add("--workdir");
        cmd.add(CONTAINER_WORKSPACE);
        if ("off".equals(s.dockerNetwork())) {
            cmd.add("--network");
            cmd.add("none");
        } else if ("preview".equals(s.dockerNetwork())) {
            for (String rawPort : s.previewPorts().split(",")) {
                String p = rawPort.trim();
                if (p.matches("\\d{2,5}")) {
                    cmd.add("-p");
                    cmd.add("127.0.0.1::" + p);
                }
            }
        }
        cmd.add("-v");
        cmd.add(realWorkspaceRoot.toString() + ":" + CONTAINER_WORKSPACE + ":rw");
        cmd.add(s.dockerImage());
        cmd.add("sleep");
        cmd.add("infinity");
        return cmd;
    }

    @Override
    public CommandRunner.CommandResult exec(List<String> argv, Path cwd, int timeoutSeconds,
                                            Path writableRoot, Map<String, String> extraEnvironment,
                                            boolean allowNetwork) {
        if (argv == null || argv.isEmpty()) {
            throw new CommandRunner.CommandRejectedException("Command must not be empty");
        }
        start();
        String containerWorkdir = toContainerWorkdir(cwd);
        Map<String, String> containerEnv = buildContainerEnv(extraEnvironment);
        List<String> dockerExec = new ArrayList<>();
        dockerExec.add(dockerBin);
        dockerExec.add("exec");
        dockerExec.add("-w");
        dockerExec.add(containerWorkdir);
        for (Map.Entry<String, String> entry : containerEnv.entrySet()) {
            dockerExec.add("-e");
            dockerExec.add(entry.getKey() + "=" + entry.getValue());
        }
        dockerExec.add(containerName);
        for (String arg : argv) {
            dockerExec.add(translateArgPath(arg));
        }
        CommandRunner.CommandResult raw = CommandRunner.execLocalProcess(
                NO_HOST_JAIL, dockerExec, spec.workspaceRoot(), timeoutSeconds,
                spec.workspaceRoot(), Map.of(), true);
        return new CommandRunner.CommandResult(
                List.copyOf(argv),
                raw.exitCode(),
                raw.stdout(),
                raw.stderr(),
                raw.timedOut(),
                raw.durationMs()
        );
    }

    @Override
    public CommandRunner.CommandResult execShell(String command, Path cwd, int timeoutSeconds,
                                                 Path writableRoot, boolean allowNetwork) {
        if (command == null || command.isBlank()) {
            throw new CommandRunner.CommandRejectedException("Command must not be blank");
        }
        CommandRunner.guard(command);
        return exec(List.of("/bin/sh", "-c", command), cwd, timeoutSeconds, writableRoot, Map.of(), allowNetwork);
    }

    @Override
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        try {
            runHostDocker(List.of(dockerBin, "rm", "-f", containerName), 10);
        } catch (RuntimeException ignored) {
            // Best-effort cleanup on shutdown
        }
    }

    @Override
    public SandboxInfo info() {
        SandboxSettings s = spec.settings();
        boolean net = !"off".equals(s.dockerNetwork());
        String desc = "docker container (" + s.dockerImage()
                + ", read-only rootfs, cap-drop=ALL, uid=10001, mem=" + s.dockerMemory()
                + ", cpus=" + s.dockerCpus() + ", pids=" + s.dockerPids()
                + ", network=" + s.dockerNetwork() + ")";
        return new SandboxInfo(
                SandboxSettings.BACKEND_DOCKER,
                "docker",
                true,
                true,
                net,
                desc,
                containerName,
                s.dockerImage()
        );
    }

    private boolean isContainerRunning() {
        try {
            CommandRunner.CommandResult res = runHostDocker(
                    List.of(dockerBin, "inspect", "-f", "{{.State.Running}}", containerName), 5);
            return res.success() && "true".equalsIgnoreCase(res.stdout().strip());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Path prepareWorkspaceDirs(Path workspaceRoot) {
        try {
            Files.createDirectories(workspaceRoot.resolve(".agent-build/tmp"));
            Files.createDirectories(workspaceRoot.resolve(".agent-build/home"));
            Files.createDirectories(workspaceRoot.resolve(".agent-build/go-cache"));
            Files.createDirectories(workspaceRoot.resolve(".agent-build/gopath"));
            Files.createDirectories(workspaceRoot.resolve(".agent-build/m2"));
            return workspaceRoot.toRealPath();
        } catch (IOException e) {
            throw new CommandRunner.CommandRejectedException(
                    "Cannot prepare workspace directories for Docker sandbox: " + e.getMessage(), e);
        }
    }

    private String toContainerWorkdir(Path cwd) {
        if (cwd == null) {
            return CONTAINER_WORKSPACE;
        }
        Path normRoot = spec.workspaceRoot().toAbsolutePath().normalize();
        Path normCwd = cwd.toAbsolutePath().normalize();
        if (normCwd.startsWith(normRoot)) {
            Path rel = normRoot.relativize(normCwd);
            String relStr = rel.toString().replace('\\', '/');
            return relStr.isEmpty() ? CONTAINER_WORKSPACE : CONTAINER_WORKSPACE + "/" + relStr;
        }
        return CONTAINER_WORKSPACE;
    }

    private String translateArgPath(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        String rootStr = spec.workspaceRoot().toAbsolutePath().normalize().toString();
        if (arg.equals(rootStr)) {
            return CONTAINER_WORKSPACE;
        }
        if (arg.startsWith(rootStr + "/")) {
            return CONTAINER_WORKSPACE + arg.substring(rootStr.length());
        }
        return arg;
    }

    private static Map<String, String> buildContainerEnv(Map<String, String> extra) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", CONTAINER_WORKSPACE + "/.agent-build/home");
        env.put("TMPDIR", CONTAINER_WORKSPACE + "/.agent-build/tmp");
        env.put("TMP", CONTAINER_WORKSPACE + "/.agent-build/tmp");
        env.put("TEMP", CONTAINER_WORKSPACE + "/.agent-build/tmp");
        env.put("GOTMPDIR", CONTAINER_WORKSPACE + "/.agent-build/tmp");
        env.put("GOCACHE", CONTAINER_WORKSPACE + "/.agent-build/go-cache");
        env.put("GOPATH", CONTAINER_WORKSPACE + "/.agent-build/gopath");
        env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + CONTAINER_WORKSPACE + "/.agent-build/m2");
        env.put("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=" + CONTAINER_WORKSPACE + "/.agent-build/tmp");
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        env.put("CI", "true");
        env.put("AGENT_SANDBOX_ACTIVE", "1");
        if (extra != null) {
            extra.forEach((k, v) -> {
                if (k != null && v != null && !CommandRunner.isSensitiveEnvName(k)) {
                    env.put(k, v);
                }
            });
        }
        return env;
    }

    private CommandRunner.CommandResult runHostDocker(List<String> cmd, int timeoutSeconds) {
        Path cwd = Files.isDirectory(spec.workspaceRoot()) ? spec.workspaceRoot() : Path.of(".");
        return CommandRunner.execLocalProcess(NO_HOST_JAIL, cmd, cwd, timeoutSeconds, cwd, Map.of(), true);
    }
}
