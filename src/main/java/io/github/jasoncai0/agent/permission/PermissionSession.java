package io.github.jasoncai0.agent.permission;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory grants for one continuous session. Grants never outlive the run.
 */
public final class PermissionSession {
    private volatile boolean workspaceWrites;
    private volatile boolean codeExecution;
    private final Set<String> networkHosts = ConcurrentHashMap.newKeySet();

    public boolean workspaceWrites() {
        return workspaceWrites;
    }

    public boolean codeExecution() {
        return codeExecution;
    }

    public boolean allowsHost(String host) {
        return host != null && networkHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    public void grant(PermissionRequest request, PermissionScope scope) {
        if (scope != PermissionScope.SESSION) {
            return;
        }
        switch (request.kind()) {
            case FILE_WRITE -> workspaceWrites = true;
            case EXECUTE -> codeExecution = true;
            case NETWORK -> {
                if (request.target() != null && !request.target().isBlank()) {
                    networkHosts.add(request.target().toLowerCase(Locale.ROOT));
                }
            }
            case COMMAND -> {
                // Dangerous and unknown commands are always asked again.
            }
        }
    }
}
