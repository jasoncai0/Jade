package io.github.jasoncai0.agent.team;

import io.github.jasoncai0.agent.agent.AgentRun;
import io.github.jasoncai0.agent.permission.PermissionRequest;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public final class TeamMember {
    public record InboxMessage(String text, boolean continuation) {
    }

    private final String id;
    private final String name;
    private final String rootRunId;
    private final String parentRunId;
    private final AgentRun run;
    private final BlockingQueue<InboxMessage> inbox = new LinkedBlockingQueue<>();
    private final Object monitor = new Object();
    private volatile boolean running;
    private volatile boolean closed;
    private volatile String currentTask = "";
    private volatile String lastResult = "";
    private volatile String lastError;

    public TeamMember(String id, String name, String rootRunId, String parentRunId, AgentRun run) {
        this.id = id;
        this.name = name;
        this.rootRunId = rootRunId;
        this.parentRunId = parentRunId;
        this.run = run;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String rootRunId() {
        return rootRunId;
    }

    public String parentRunId() {
        return parentRunId;
    }

    public AgentRun run() {
        return run;
    }

    public BlockingQueue<InboxMessage> inbox() {
        return inbox;
    }

    public Object monitor() {
        return monitor;
    }

    public boolean running() {
        return running;
    }

    public void running(boolean value) {
        running = value;
    }

    public boolean closed() {
        return closed;
    }

    public void close() {
        closed = true;
        inbox.offer(new InboxMessage("", false));
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    public String currentTask() {
        return currentTask;
    }

    public void currentTask(String value) {
        currentTask = value == null ? "" : value;
    }

    public String lastResult() {
        return lastResult;
    }

    public void lastResult(String value) {
        lastResult = value == null ? "" : value;
    }

    public String lastError() {
        return lastError;
    }

    public void lastError(String value) {
        lastError = value == null || value.isBlank() ? null : value;
    }

    public PermissionRequest pendingPermission() {
        return run.state().pendingPermission();
    }
}
