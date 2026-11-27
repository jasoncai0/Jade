package io.github.jasoncai0.agent.sandbox;

/** ServiceLoader SPI for execution backends ("local", "docker", ...). */
public interface SandboxProvider {
    String id();

    boolean available();

    default String availabilityDetail() {
        return available() ? "ready" : "unavailable";
    }

    SandboxSession create(SandboxSpec spec);
}
