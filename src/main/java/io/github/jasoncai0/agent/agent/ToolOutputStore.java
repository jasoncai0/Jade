package io.github.jasoncai0.agent.agent;

import io.github.jasoncai0.agent.workspace.WorkspaceException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Persists large tool outputs under {@code .agent/tool-results/<runId>/} and
 * produces the head+tail inline view the model sees.
 *
 * <p>The store is deliberately dumb: it keeps every spilled file and never
 * deletes one. Cleanup is a deliberate operator action so a run never loses
 * evidence it already produced.
 */
public final class ToolOutputStore {
    public static final int MAX_INLINE_CHARS = 30_000;
    public static final int INLINE_HEAD_CHARS = 20_000;
    public static final int INLINE_TAIL_CHARS = 9_000;
    public static final long MAX_READ_START = 5_000_000;
    public static final int MAX_READ_CHARS = 65_536;
    public static final int DEFAULT_READ_CHARS = 16_384;

    private static final String SPILL_DIR = ".agent/tool-results";
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;
    private final Path realRoot;
    private final Path spillRoot;

    public ToolOutputStore(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new WorkspaceException("Workspace root must not be null");
        }
        this.root = workspaceRoot.toAbsolutePath().normalize();
        this.spillRoot = root.resolve(SPILL_DIR).normalize();
        try {
            Files.createDirectories(root);
            this.realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("Cannot open tool-output workspace: " + e.getMessage(), e);
        }
    }

    public ToolOutputRef save(String runId, long seq, String toolName, String output) {
        String text = output == null ? "" : output;
        if (text.length() <= MAX_INLINE_CHARS) {
            return new ToolOutputRef("", 0, text.length(), false);
        }

        String safeRun = safeSegment(runId, "run", 64);
        String safeTool = safeSegment(toolName, "tool", 40);
        long safeSeq = Math.max(0, seq);
        Path directory = spillRoot.resolve(safeRun).normalize();
        if (!directory.startsWith(spillRoot)) {
            throw new WorkspaceException("Invalid spill directory for run: " + runId);
        }
        Path target = directory.resolve(safeSeq + "-" + safeTool + ".txt").normalize();
        if (!target.startsWith(directory)) {
            throw new WorkspaceException("Invalid spill file for tool: " + toolName);
        }

        try {
            Files.createDirectories(directory);
            Files.writeString(target, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(target, OWNER_ONLY);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem; the workspace jail still applies.
            }
            String relative = root.relativize(target).toString().replace('\\', '/');
            return new ToolOutputRef(relative, Files.size(target), text.length(), true);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot spill tool output: " + e.getMessage(), e);
        }
    }

    public String inline(String output, ToolOutputRef ref) {
        String text = output == null ? "" : output;
        if (ref == null || !ref.spilled() || text.length() <= MAX_INLINE_CHARS) {
            return text;
        }
        String head = text.substring(0, INLINE_HEAD_CHARS);
        String tail = text.substring(text.length() - INLINE_TAIL_CHARS);
        String marker = "\n... [output truncated: " + ref.originalChars()
                + " chars; full text: " + ref.relativePath()
                + "; read with read_tool_output] ...\n";
        return head + marker + tail;
    }

    public String read(String relativePath, long startChar, int maxChars) {
        if (startChar < 0 || startChar > MAX_READ_START) {
            throw new WorkspaceException("startChar must be between 0 and " + MAX_READ_START);
        }
        if (maxChars < 1 || maxChars > MAX_READ_CHARS) {
            throw new WorkspaceException("maxChars must be between 1 and " + MAX_READ_CHARS);
        }
        Path file = resolveSpillFile(relativePath);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            long remaining = startChar;
            while (remaining > 0) {
                long skipped = reader.skip(remaining);
                if (skipped > 0) {
                    remaining -= skipped;
                } else if (reader.read() < 0) {
                    return "";
                } else {
                    remaining--;
                }
            }
            char[] buffer = new char[maxChars];
            int total = 0;
            while (total < maxChars) {
                int read = reader.read(buffer, total, maxChars - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return new String(buffer, 0, total);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot read tool output " + relativePath + ": " + e.getMessage(), e);
        }
    }

    public int totalChars(String relativePath) {
        Path file = resolveSpillFile(relativePath);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8_192];
            long total = 0;
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                total += read;
                if (total > Integer.MAX_VALUE) {
                    throw new WorkspaceException("Tool output is too large to index: " + relativePath);
                }
            }
            return (int) total;
        } catch (IOException e) {
            throw new WorkspaceException("Cannot read tool output " + relativePath + ": " + e.getMessage(), e);
        }
    }

    private Path resolveSpillFile(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new WorkspaceException("Tool output path must not be blank");
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new WorkspaceException("Tool output path contains a NUL byte");
        }
        Path requested;
        try {
            requested = Path.of(relativePath);
        } catch (RuntimeException e) {
            throw new WorkspaceException("Invalid tool output path: " + relativePath);
        }
        if (requested.isAbsolute()) {
            throw new WorkspaceException("Absolute tool output paths are not allowed: " + relativePath);
        }
        Path candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(spillRoot)) {
            throw new WorkspaceException("Tool output path escapes " + SPILL_DIR + ": " + relativePath);
        }
        rejectSymlinks(candidate);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("Tool output does not exist: " + relativePath);
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("Not a regular tool-output file: " + relativePath);
        }
        try {
            if (!candidate.toRealPath().startsWith(realRoot)) {
                throw new WorkspaceException("Tool output resolves outside the workspace: " + relativePath);
            }
        } catch (IOException e) {
            throw new WorkspaceException("Cannot resolve tool output " + relativePath + ": " + e.getMessage(), e);
        }
        return candidate;
    }

    private void rejectSymlinks(Path candidate) {
        Path cursor = root;
        for (Path part : root.relativize(candidate)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new WorkspaceException("Tool output path traverses a symlink: " + candidate);
            }
        }
    }

    private static String safeSegment(String value, String fallback, int max) {
        String cleaned = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        while (cleaned.startsWith(".")) {
            cleaned = "_" + cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            cleaned = fallback;
        }
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
