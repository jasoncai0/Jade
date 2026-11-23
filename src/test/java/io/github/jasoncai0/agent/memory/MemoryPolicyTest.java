package io.github.jasoncai0.agent.memory;

import io.github.jasoncai0.agent.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPolicyTest {
    private static final MemoryScope SCOPE = new MemoryScope("u", "jade", "project-a", "demo", "run-1");

    @Test
    void acceptsExplicitPreferencesAndProjectDecisions() {
        MemoryCandidate preference = MemoryPolicy.candidateFromUserMessage(
                "以后这个项目默认用 4 个空格缩进。", SCOPE).orElseThrow();
        assertEquals("preference", preference.kind());
        assertTrue(preference.infer());
        assertFalse(preference.global());

        MemoryCandidate fact = MemoryPolicy.candidateFromUserMessage(
                "这个项目约定测试走 Maven，不要用 Gradle。", SCOPE).orElseThrow();
        assertEquals("project_fact", fact.kind());
    }

    @Test
    void rejectsSecretsAndSmallTalk() {
        assertTrue(MemoryPolicy.candidateFromUserMessage("hello", SCOPE).isEmpty());
        assertTrue(MemoryPolicy.candidateFromUserMessage(
                "以后记住 password=super-secret", SCOPE).isEmpty());
        assertTrue(MemoryPolicy.candidateFromUserMessage(
                "以后记住 key sk-abcdefghijklmnop", SCOPE).isEmpty());
    }

    @Test
    void keepsProjectItemsAndAtMostTwoGlobalItems() {
        List<MemoryItem> items = List.of(
                item("p1", 0.9, "project"),
                item("p2", 0.8, "project"),
                item("g1", 0.7, "global"),
                item("g2", 0.6, "global"),
                item("g3", 0.5, "global"),
                item("g4", 0.4, "global"),
                item("p3", 0.9, "project")
        );
        List<MemoryItem> selected = MemoryPolicy.select(items, SCOPE);
        assertEquals(5, selected.size());
        assertEquals(2, selected.stream().filter(MemoryItem::global).count());
        assertTrue(selected.stream().anyMatch(item -> "p1".equals(item.id())));
    }

    @Test
    void injectsMemoryOnceAndReplacesPreviousBlock() {
        String block = MemoryContext.block(List.of(item("p1", 0.9, "project")));
        List<ChatMessage> once = MemoryContext.inject(List.of(
                ChatMessage.system("base"), ChatMessage.user("hello")), block);
        assertEquals(2, once.size());
        assertTrue(once.get(0).content().contains(MemoryContext.MARKER));
        assertTrue(once.get(0).content().startsWith("base"));

        List<ChatMessage> twice = MemoryContext.inject(once, block);
        assertEquals(2, twice.size());
        assertEquals(1, twice.get(0).content().split(MemoryContext.MARKER, -1).length - 1);
    }

    private static MemoryItem item(String id, double score, String scope) {
        return new MemoryItem(id, "text " + id, score, "fact", scope,
                "global".equals(scope) ? "global" : "project-a", "test", "", java.util.Map.of());
    }
}
