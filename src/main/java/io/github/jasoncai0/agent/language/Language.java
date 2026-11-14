package io.github.jasoncai0.agent.language;

import io.github.jasoncai0.agent.workspace.Workspace;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A language the agent can verify and execute. The enum is deliberately small:
 * each entry maps to one toolchain, one source extension, and conventional
 * entry-point names.
 */
public enum Language {
    JAVA("java", "Java", ".java", List.of("Main.java", "src/Main.java")),
    PYTHON("python", "Python", ".py", List.of("main.py", "src/main.py", "app.py")),
    JAVASCRIPT("javascript", "JavaScript", ".js", List.of("main.js", "index.js", "src/main.js"));

    private final String id;
    private final String displayName;
    private final String extension;
    private final List<String> entryCandidates;

    Language(String id, String displayName, String extension, List<String> entryCandidates) {
        this.id = id;
        this.displayName = displayName;
        this.extension = extension;
        this.entryCandidates = entryCandidates;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String extension() {
        return extension;
    }

    public List<String> entryCandidates() {
        return entryCandidates;
    }

    public static List<String> ids() {
        return List.of(values()).stream().map(Language::id).toList();
    }

    public static List<String> displayNames() {
        return List.of(values()).stream().map(Language::displayName).toList();
    }

    /**
     * Parses a user/model supplied language id. Accepts a few shell-friendly
     * aliases, but never guesses from a file extension here.
     */
    public static Optional<Language> fromId(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "java" -> Optional.of(JAVA);
            case "python", "python3", "py" -> Optional.of(PYTHON);
            case "javascript", "js", "node", "nodejs" -> Optional.of(JAVASCRIPT);
            default -> Optional.empty();
        };
    }

    public static Language fromPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return JAVA;
        }
        if (lower.endsWith(".py")) {
            return PYTHON;
        }
        if (lower.endsWith(".js")) {
            return JAVASCRIPT;
        }
        return null;
    }

    /**
     * Chooses the primary language by source count. Ties follow declaration
     * order (Java, Python, JavaScript), which keeps a repository with a stray
     * README sample deterministic.
     */
    public static Language detect(List<Path> sources) {
        Map<Language, Integer> counts = new LinkedHashMap<>();
        for (Language language : values()) {
            counts.put(language, 0);
        }
        for (Path source : sources) {
            Language language = fromPath(source.getFileName().toString());
            if (language != null) {
                counts.merge(language, 1, Integer::sum);
            }
        }
        Language winner = null;
        int best = 0;
        for (Map.Entry<Language, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                winner = entry.getKey();
            }
        }
        return winner;
    }

    public List<Path> sources(Workspace workspace) {
        return workspace.sourceFiles(extension);
    }

    /**
     * Returns the conventional entry point, or the only source file when a
     * conventional path is absent.
     */
    public String defaultEntry(Workspace workspace) {
        for (String candidate : entryCandidates) {
            if (workspace.exists(candidate) && !workspace.resolve(candidate).toFile().isDirectory()) {
                return candidate;
            }
        }
        List<Path> sources = sources(workspace);
        return sources.isEmpty() ? null : workspace.relativize(sources.get(0));
    }
}
