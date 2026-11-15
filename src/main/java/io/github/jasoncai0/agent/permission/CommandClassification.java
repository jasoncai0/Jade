package io.github.jasoncai0.agent.permission;

public record CommandClassification(
        Category category,
        String summary,
        String host,
        int port,
        String hardDenyReason
) {
    public enum Category {
        READ,
        WORKSPACE_WRITE,
        EXECUTE,
        DANGEROUS,
        NETWORK,
        UNKNOWN
    }

    public boolean hardDenied() {
        return hardDenyReason != null && !hardDenyReason.isBlank();
    }

    public boolean network() {
        return category == Category.NETWORK;
    }

    public static CommandClassification of(Category category, String summary, String host, int port) {
        return new CommandClassification(category, summary, host, port, null);
    }

    public static CommandClassification hardDenied(String reason) {
        return new CommandClassification(Category.DANGEROUS, reason, null, 0, reason);
    }
}
