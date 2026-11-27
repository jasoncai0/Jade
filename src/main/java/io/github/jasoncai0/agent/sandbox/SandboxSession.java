package io.github.jasoncai0.agent.sandbox;

import io.github.jasoncai0.agent.workspace.CommandRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Per-run command execution boundary (local process jail or container session). */
public interface SandboxSession extends AutoCloseable {
    void start();

    CommandRunner.CommandResult exec(List<String> argv, Path cwd, int timeoutSeconds,
                                     Path writableRoot, Map<String, String> extraEnvironment,
                                     boolean allowNetwork);

    CommandRunner.CommandResult execShell(String command, Path cwd, int timeoutSeconds,
                                          Path writableRoot, boolean allowNetwork);

    void stop();

    SandboxInfo info();

    @Override
    default void close() {
        stop();
    }
}
