package io.github.jasoncai0.agent.tool;

import java.util.List;

/**
 * Third-party tools are discovered with ServiceLoader. A provider can be packaged in
 * an external jar and dropped on the classpath without editing the agent.
 */
public interface ToolProvider {
    List<Tool> tools();
}
