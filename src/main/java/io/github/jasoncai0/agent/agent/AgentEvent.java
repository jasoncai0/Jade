package io.github.jasoncai0.agent.agent;

import java.util.Map;

public record AgentEvent(long seq, long timestamp, String type, Map<String, Object> data) {
}
