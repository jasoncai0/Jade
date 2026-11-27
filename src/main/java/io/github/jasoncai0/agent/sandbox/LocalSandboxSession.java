package io.github.jasoncai0.agent.sandbox;

import io.github.jasoncai0.agent.workspace.CommandRunner;
import io.github.jasoncai0.agent.workspace.CommandSandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Built-in local execution session delegating to {@link CommandSandbox} (default path). */
public final class LocalSandboxSession implements SandboxSession {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase().contains("win");

    private final CommandSandbox commandSandbox;

    public LocalSandboxSession(CommandSandbox commandSandbox) {
        this.commandSandbox = commandSandbox == null ? CommandSandbox.fromEnvironment() : commandSandbox;
    }

    public CommandSandbox commandSandbox() {
        return commandSandbox;
    }

    @Override
    public void start() {
        // Local sessions spawn processes per command; no daemon setup needed.
    }

    @Override
    public CommandRunner.CommandResult exec(List<String> argv, Path cwd, int timeoutSeconds,
                                            Path writableRoot, Map<String, String> extraEnvironment,
                                            boolean allowNetwork) {
        return CommandRunner.execLocalProcess(
                commandSandbox, argv, cwd, timeoutSeconds, writableRoot, extraEnvironment, allowNetwork);
    }

    @Override
    public CommandRunner.CommandResult execShell(String command, Path cwd, int timeoutSeconds,
                                                 Path writableRoot, boolean allowNetwork) {
        if (command == null || command.isBlank()) {
            throw new CommandRunner.CommandRejectedException("Command must not be blank");
        }
        CommandRunner.guard(command);
        List<String> argv = WINDOWS
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
        return exec(argv, cwd, timeoutSeconds, writableRoot, Map.of(), allowNetwork);
    }

    @Override
    public void stop() {
        // No persistent container to tear down in local mode.
    }

    @Override
    public SandboxInfo info() {
        return new SandboxInfo(
                SandboxSettings.BACKEND_LOCAL,
                commandSandbox.modeName(),
                commandSandbox.active(),
                false,
                commandSandbox.networkEnabled(),
                commandSandbox.description(),
                "",
                ""
        );
    }
}
