package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.concurrent.CompletableFuture;

/**
 * A submitted run: state for the UI, the event stream, and cancellation handle.
 */
public final class AgentRun {
    private final String id;
    private final Workspace workspace;
    private final RunState state;
    private final RunEventLog events;
    private final RunControl control;
    private final CompletableFuture<RunSnapshot> completion;
    private final long createdAt = System.currentTimeMillis();

    AgentRun(String id, Workspace workspace, RunState state, RunEventLog events,
             RunControl control, CompletableFuture<RunSnapshot> completion) {
        this.id = id;
        this.workspace = workspace;
        this.state = state;
        this.events = events;
        this.control = control;
        this.completion = completion;
    }

    public String id() {
        return id;
    }

    public Workspace workspace() {
        return workspace;
    }

    public RunState state() {
        return state;
    }

    public RunEventLog events() {
        return events;
    }

    public RunControl control() {
        return control;
    }

    public CompletableFuture<RunSnapshot> completion() {
        return completion;
    }

    public long createdAt() {
        return createdAt;
    }

    public RunSnapshot snapshot() {
        return state.snapshot();
    }
}
