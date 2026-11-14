package io.github.jasoncai0.agent.skill;

import io.github.jasoncai0.agent.config.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Discovers SKILL.md folders at startup. Skills are prompt fragments, not executable plugins.
 */
public final class SkillRegistry {
    private static final long MAX_SKILL_BYTES = 128 * 1024;
    private final Path skillsDir;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(Path skillsDir) {
        this.skillsDir = skillsDir.toAbsolutePath().normalize();
    }

    public static SkillRegistry load(Path skillsDir) {
        SkillRegistry registry = new SkillRegistry(skillsDir);
        registry.discover();
        return registry;
    }

    public void discover() {
        skills.clear();
        if (!Files.isDirectory(skillsDir)) {
            Log.warn("skills.missing_dir", Map.of("path", skillsDir.toString()));
            return;
        }
        try (Stream<Path> stream = Files.walk(skillsDir, 3)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                try {
                    Skill skill = parse(file);
                    if (skills.putIfAbsent(skill.name(), skill) != null) {
                        Log.warn("skills.duplicate", Map.of("name", skill.name(), "file", file.toString()));
                    }
                } catch (RuntimeException e) {
                    Log.warn("skills.invalid", Map.of("file", file.toString(), "error", e.getMessage()));
                }
            }
        } catch (IOException e) {
            Log.warn("skills.scan_failed", Map.of("path", skillsDir.toString(), "error", e.getMessage()));
        }
        Log.info("skills.discovered", Map.of("count", skills.size(), "path", skillsDir.toString()));
    }

    public List<Skill> all() {
        return skills.values().stream()
                .sorted(Comparator.comparing(Skill::name))
                .toList();
    }

    public Optional<Skill> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(name.strip()));
    }

    public List<Skill> select(String task, int limit) {
        return skills.values().stream()
                .map(skill -> Map.entry(skill, skill.score(task)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Skill, Integer>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().name()))
                .limit(Math.max(0, limit))
                .map(Map.Entry::getKey)
                .toList();
    }

    public String catalog() {
        if (skills.isEmpty()) {
            return "(no skills installed)";
        }
        StringBuilder out = new StringBuilder();
        for (Skill skill : all()) {
            out.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    public Path skillsDir() {
        return skillsDir;
    }

    private Skill parse(Path file) {
        try {
            if (Files.size(file) > MAX_SKILL_BYTES) {
                throw new IllegalArgumentException("skill file is larger than " + MAX_SKILL_BYTES + " bytes");
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Map<String, Object> frontmatter = new LinkedHashMap<>();
            String body = raw;
            if (raw.startsWith("---\n")) {
                int end = raw.indexOf("\n---", 4);
                if (end < 0) {
                    throw new IllegalArgumentException("frontmatter has no closing ---");
                }
                String header = raw.substring(4, end);
                body = raw.substring(end + 4).stripLeading();
                frontmatter = parseFrontmatter(header);
            }
            String fallbackName = file.getParent().getFileName().toString();
            String name = scalar(frontmatter, "name", fallbackName);
            String description = scalar(frontmatter, "description", firstParagraph(body));
            List<String> tags = list(frontmatter.get("tags"));
            List<String> triggers = list(frontmatter.get("triggers"));
            if (triggers.isEmpty()) {
                triggers = tags;
            }
            return new Skill(name, description, tags, triggers, body,
                    skillsDir.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/'));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> parseFrontmatter(String header) {
        Map<String, Object> values = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentList = null;
        for (String line : header.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ") && currentKey != null) {
                if (currentList == null) {
                    currentList = new ArrayList<>();
                    values.put(currentKey, currentList);
                }
                currentList.add(unquote(trimmed.substring(2).strip()));
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colon + 1).strip();
            currentKey = key;
            currentList = null;
            if (value.startsWith("[") && value.endsWith("]")) {
                List<String> inline = new ArrayList<>();
                for (String item : value.substring(1, value.length() - 1).split(",")) {
                    if (!item.isBlank()) {
                        inline.add(unquote(item.strip()));
                    }
                }
                values.put(key, inline);
            } else if (value.isEmpty()) {
                values.put(key, new ArrayList<String>());
            } else {
                values.put(key, unquote(value));
            }
        }
        return values;
    }

    private static String scalar(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.strip();
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        if (value instanceof List<?> items) {
            return items.stream()
                    .map(String::valueOf)
                    .map(String::strip)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.strip());
        }
        return List.of();
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String firstParagraph(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("---")) {
                return trimmed;
            }
        }
        return "No description";
    }
}
