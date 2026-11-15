package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.permission.PermissionSession;
import io.github.jasoncai0.agent.workspace.Workspace;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A session: one isolated workspace, one durable transcript, and a sequence of
 * user turns. The event log stays open between turns so the browser can keep a
 * single SSE connection while the user iterates.
 */
public final class AgentRun {
    private final String id;
    private final Workspace workspace;
    private final RunState state;
    private final RunEventLog events;
    private final PermissionBroker permissions = new PermissionBroker();
    private final PermissionSession permissionSession = new PermissionSession();
    private final AtomicBoolean running = new AtomicBoolean();
    private final long createdAt = System.currentTimeMillis();
    private volatile RunControl control = new RunControl();
    private volatile CompletableFuture<RunSnapshot> completion;

    AgentRun(String id, Workspace workspace, RunState state, RunEventLog events) {
        this.id = id;
        this.workspace = workspace;
        this.state = state;
        this.events = events;
        this.completion = CompletableFuture.completedFuture(state.snapshot());
    }

    /**
     * Reserves the session for one executor turn. Returns false when another
     * turn is already running.
     */
    public boolean beginTurn(RunControl nextControl, CompletableFuture<RunSnapshot> nextCompletion) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        this.control = nextControl;
        this.completion = nextCompletion;
        return true;
    }

    public void finishTurn() {
        running.set(false);
    }

    public boolean running() {
        return running.get();
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

    public PermissionBroker permissions() {
        return permissions;
    }

    public PermissionSession permissionSession() {
        return permissionSession;
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
