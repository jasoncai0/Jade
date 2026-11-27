package io.github.jasoncai0.agent.sandbox;

import io.github.jasoncai0.agent.workspace.CommandSandbox;

/** Built-in provider preserving local host execution as Jade's default. */
public final class LocalSandboxProvider implements SandboxProvider {
    @Override
    public String id() {
        return SandboxSettings.BACKEND_LOCAL;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String availabilityDetail() {
        return CommandSandbox.fromEnvironment().description();
    }

    @Override
    public SandboxSession create(SandboxSpec spec) {
        return new LocalSandboxSession(CommandSandbox.fromEnvironment());
    }
}
