package io.github.jasoncai0.agent.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All file access goes through this class. It is a path jail, not an OS sandbox:
 * shell commands still run as the current user.
 */
public final class Workspace {
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".gradle", ".mvn", ".agent", ".agent-build",
            "target", "build", "out", "bin", "node_modules", "dist",
            "__pycache__", ".venv", "venv", ".pytest_cache", ".mypy_cache"
    );
    private static final long MAX_READ_BYTES = 512 * 1024;
    private static final long MAX_WRITE_BYTES = 1024 * 1024;

    private final Path root;
    private final Path realRoot;

    private Workspace(Path root, Path realRoot) {
        this.root = root;
        this.realRoot = realRoot;
    }

    public static Workspace open(Path root) {
        try {
            Path normalized = root.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            Path real = normalized.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new WorkspaceException("Workspace path is not a directory: " + normalized);
            }
            return new Workspace(normalized, real);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot open workspace " + root + ": " + e.getMessage(), e);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            return root;
        }
        String cleaned = relative.trim();
        if (cleaned.indexOf('\0') >= 0) {
            throw new WorkspaceException("Path contains a NUL byte");
        }
        Path requested;
        try {
            requested = Path.of(cleaned);
        } catch (RuntimeException e) {
            throw new WorkspaceException("Invalid path: " + cleaned);
        }
        if (requested.isAbsolute()) {
            throw new WorkspaceException("Absolute paths are not allowed: " + cleaned);
        }
        Path candidate = root.resolve(requested).normalize();
        if (!candidate.startsWith(root)) {
            throw new WorkspaceException("Path escapes the workspace: " + cleaned);
        }
        assertInsideRealRoot(candidate);
        return candidate;
    }

    public String relativize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return normalized.toString();
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private void assertInsideRealRoot(Path candidate) {
        Path probe = candidate;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw new WorkspaceException("Path does not have an existing parent inside the workspace");
        }
        try {
            Path real = probe.toRealPath();
            if (!real.startsWith(realRoot)) {
                throw new WorkspaceException("Path resolves outside the workspace through a symlink: " + candidate);
            }
        } catch (IOException e) {
            throw new WorkspaceException("Cannot resolve path " + candidate + ": " + e.getMessage(), e);
        }
    }

    public boolean exists(String relative) {
        return Files.exists(resolve(relative), LinkOption.NOFOLLOW_LINKS);
    }

    public long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot read file size: " + e.getMessage(), e);
        }
    }

    public String readText(String relative) {
        return readText(resolve(relative));
    }

    public String readText(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new WorkspaceException("Not a regular file: " + relativize(file));
            }
            long size = Files.size(file);
            if (size > MAX_READ_BYTES) {
                throw new WorkspaceException("File is larger than " + MAX_READ_BYTES + " bytes: " + relativize(file));
            }
            byte[] bytes = Files.readAllBytes(file);
            if (isProbablyBinary(bytes)) {
                throw new WorkspaceException("Binary file cannot be read as text: " + relativize(file));
            }
            return decodeStrict(bytes);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot read " + relativize(file) + ": " + e.getMessage(), e);
        }
    }

    public int writeText(String relative, String content) {
        if (content == null) {
            throw new WorkspaceException("Content must not be null");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            throw new WorkspaceException("Refusing to write more than " + MAX_WRITE_BYTES + " bytes");
        }
        Path target = resolve(relative);
        if (Files.isDirectory(target)) {
            throw new WorkspaceException("Cannot overwrite a directory: " + relative);
        }
        Path parent = target.getParent();
        Path temp = null;
        try {
            Files.createDirectories(parent);
            temp = Files.createTempFile(parent, ".agent-write-", ".tmp");
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            return bytes.length;
        } catch (IOException e) {
            throw new WorkspaceException("Cannot write " + relative + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    public List<FileEntry> listFiles(int maxEntries, int maxDepth) {
        List<FileEntry> entries = new ArrayList<>();
        int limit = Math.max(1, Math.min(maxEntries, 5000));
        int depth = Math.max(0, Math.min(maxDepth, 20));
        try {
            Files.walkFileTree(root, Set.of(), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && shouldSkip(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root) && entries.size() < limit) {
                        entries.add(new FileEntry(relativize(dir), 0, true));
                    }
                    return entries.size() < limit ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink() && entries.size() < limit) {
                        entries.add(new FileEntry(relativize(file), attrs.size(), false));
                    }
                    return entries.size() < limit ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new WorkspaceException("Cannot list workspace: " + e.getMessage(), e);
        }
        entries.sort(Comparator.comparing(FileEntry::path));
        return entries;
    }

    public String tree(int maxEntries, int maxDepth) {
        List<FileEntry> entries = listFiles(maxEntries, maxDepth);
        if (entries.isEmpty()) {
            return "(empty workspace)";
        }
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (FileEntry entry : entries) {
            out.append(entry.directory() ? "[d] " : "    ")
                    .append(entry.path());
            if (!entry.directory()) {
                out.append("  (").append(entry.size()).append("B)");
            }
            out.append('\n');
            count++;
            if (count >= maxEntries) {
                out.append("... (tree truncated)\n");
                break;
            }
        }
        return out.toString();
    }

    public List<Path> javaSources() {
        return sourceFiles(".java");
    }

    /**
     * Finds source files by suffix, skipping build, dependency, cache, and
     * agent-internal directories. Used by the polyglot verifier.
     */
    public List<Path> sourceFiles(String... extensions) {
        List<String> suffixes = new ArrayList<>();
        for (String extension : extensions) {
            if (extension != null && !extension.isBlank()) {
                suffixes.add(extension.startsWith(".") ? extension : "." + extension);
            }
        }
        if (suffixes.isEmpty()) {
            return List.of();
        }
        List<Path> sources = new ArrayList<>();
        try {
            Files.walkFileTree(root, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && shouldSkip(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    for (String suffix : suffixes) {
                        if (name.endsWith(suffix)) {
                            sources.add(file);
                            break;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new WorkspaceException("Cannot scan workspace sources: " + e.getMessage(), e);
        }
        sources.sort(Comparator.comparing(this::relativize));
        return sources;
    }

    public Path classesDir() {
        Path dir = root.resolve(".agent-build/classes");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new WorkspaceException("Cannot create build directory: " + e.getMessage(), e);
        }
        return dir;
    }

    private boolean shouldSkip(Path dir) {
        String name = dir.getFileName().toString();
        return SKIP_DIRS.contains(name) || name.startsWith(".agent-");
    }

    private static boolean isProbablyBinary(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        for (int i = 0; i < sample; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String decodeStrict(byte[] bytes) {
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new WorkspaceException("File is not valid UTF-8 text");
        }
    }

    public record FileEntry(String path, long size, boolean directory) {
    }
}
