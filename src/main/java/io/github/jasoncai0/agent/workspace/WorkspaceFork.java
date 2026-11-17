package io.github.jasoncai0.agent.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Copies a finished workspace into a fresh directory for a forked session.
 *
 * <p>Build output, VCS metadata and the agent's own scratch state are skipped:
 * a fork is source plus persisted tool output, not a byte-for-byte clone. The
 * copy is bounded so a runaway workspace cannot fill the disk.</p>
 */
public final class WorkspaceFork {
    private static final int MAX_FILES = 20_000;
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static final int MAX_TOOL_RESULT_FILES = 5_000;
    private static final long MAX_TOOL_RESULT_BYTES = 200L * 1024 * 1024;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".agent", ".agent-build", ".idea", ".gradle", ".mvn",
            "target", "build", "out", "bin", "node_modules", "dist",
            "__pycache__", ".venv", "venv", ".pytest_cache", ".mypy_cache",
            ".pnpm-store", ".yarn", "vendor", "third_party"
    );

    private WorkspaceFork() {
    }

    public record Result(int files, long bytes, int toolResultFiles) {
    }

    /**
     * Copies {@code source} into the existing, empty {@code target}. On failure
     * the partial target is removed before the exception propagates.
     */
    public static Result copy(Path source, Path target) {
        if (!Files.isDirectory(source)) {
            throw new WorkspaceException("Source workspace does not exist: " + source);
        }
        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(source) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new IOException("Fork refuses to follow symlink: " + source.relativize(file));
                    }
                    long size = Files.size(file);
                    int count = files.incrementAndGet();
                    long total = bytes.addAndGet(size);
                    if (count > MAX_FILES) {
                        throw new IOException("Fork exceeds " + MAX_FILES + " files");
                    }
                    if (total > MAX_BYTES) {
                        throw new IOException("Fork exceeds " + MAX_BYTES + " bytes");
                    }
                    Path destination = target.resolve(source.relativize(file).toString());
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            deleteQuietly(target);
            throw new WorkspaceException("Cannot fork workspace: " + e.getMessage(), e);
        }

        // Spilled tool output is addressed by relative path from the transcript;
        // keep it so read_tool_output still resolves in the forked session.
        int toolResultFiles = copyToolResults(source, target);
        return new Result(files.get(), bytes.get(), toolResultFiles);
    }

    private static int copyToolResults(Path source, Path target) {
        Path from = source.resolve(".agent").resolve("tool-results");
        if (!Files.isDirectory(from)) {
            return 0;
        }
        Path to = target.resolve(".agent").resolve("tool-results");
        AtomicInteger count = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.sorted().toList()) {
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                    continue;
                }
                long size = Files.size(file);
                if (count.incrementAndGet() > MAX_TOOL_RESULT_FILES
                        || bytes.addAndGet(size) > MAX_TOOL_RESULT_BYTES) {
                    break;
                }
                Path destination = to.resolve(from.relativize(file).toString());
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Tool output is optional; a fork with a partial spill store still works.
        }
        return count.get();
    }

    private static void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
