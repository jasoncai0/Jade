package io.github.jasoncai0.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {
    @TempDir
    Path temp;

    @Test
    void discoversFrontmatterAndSelectsByTaskRelevance() throws Exception {
        skill("debugger", """
                ---
                name: debugger
                description: Fix Java compiler errors.
                tags: [java, debug]
                triggers:
                  - compiler
                  - stack trace
                ---
                # Debug
                Read the exact compiler line.
                """);
        skill("testing", """
                ---
                name: testing
                description: Add assertions and tests.
                tags: [java, testing]
                triggers:
                  - test
                  - assertion
                ---
                # Test
                Write a failing check first.
                """);

        SkillRegistry registry = SkillRegistry.load(temp.resolve("skills"));

        assertEquals(2, registry.all().size());
        assertEquals("debugger", registry.find("debugger").orElseThrow().name());
        List<Skill> selected = registry.select("fix compiler error and stack trace", 2);
        assertEquals("debugger", selected.get(0).name());
        assertTrue(registry.catalog().contains("testing: Add assertions"));
        assertTrue(registry.find("debugger").orElseThrow().body().contains("Read the exact compiler line."));
        assertFalse(registry.select("unrelated task", 2).size() > 0);
    }

    @Test
    void usesFolderNameAndFirstParagraphWithoutFrontmatter() throws Exception {
        Path dir = temp.resolve("skills/plain");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# Plain\n\nA fallback description.\n");

        SkillRegistry registry = SkillRegistry.load(temp.resolve("skills"));
        Skill skill = registry.find("plain").orElseThrow();

        assertEquals("plain", skill.name());
        assertEquals("A fallback description.", skill.description());
    }

    @Test
    void keepsFirstSkillWhenNamesCollide() throws Exception {
        skill("a", """
                ---
                name: duplicate
                description: first
                ---
                first body
                """);
        skill("b", """
                ---
                name: duplicate
                description: second
                ---
                second body
                """);

        SkillRegistry registry = SkillRegistry.load(temp.resolve("skills"));

        assertEquals(1, registry.all().size());
        assertEquals("first", registry.find("duplicate").orElseThrow().description());
    }

    private void skill(String folder, String content) throws Exception {
        Path dir = temp.resolve("skills").resolve(folder);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
