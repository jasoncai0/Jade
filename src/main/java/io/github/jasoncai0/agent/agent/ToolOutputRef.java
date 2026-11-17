package io.github.jasoncai0.agent.agent;

/**
 * A reference to a full tool output that was spilled to disk.
 *
 * @param relativePath path relative to the run workspace, using forward slashes
 * @param bytes        UTF-8 size of the spill file
 * @param originalChars number of characters in the original output
 * @param spilled      false when the output was small enough to stay inline
 */
public record ToolOutputRef(String relativePath, long bytes, int originalChars, boolean spilled) {
    public ToolOutputRef {
        relativePath = relativePath == null ? "" : relativePath;
    }
}
