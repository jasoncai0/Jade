package io.github.jasoncai0.agent.tool;

import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConcurrencyTest {
    @Test
    void onlyTheExplicitReadOnlyWhitelistIsConcurrencySafe() {
        Set<String> safe = BuiltInTools.all().stream()
                .filter(Tool::concurrencySafe)
                .map(tool -> tool.spec().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of("list_files", "read_file", "search_text", "load_skill"), safe);
    }

    @Test
    void everyOtherBuiltInStaysSerial() {
        for (Tool tool : BuiltInTools.all()) {
            if (Set.of("list_files", "read_file", "search_text", "load_skill")
                    .contains(tool.spec().name())) {
                continue;
            }
            assertFalse(tool.concurrencySafe(), tool.spec().name());
        }
    }

    @Test
    void registryReturnsFalseForUnknownTools() {
        ToolRegistry registry = new ToolRegistry();
        assertFalse(registry.concurrencySafe("does_not_exist"));
    }

    @Test
    void mutatesWorkspaceIsNotThePredicate() {
        Tool read = BuiltInTools.all().stream()
                .filter(tool -> tool.spec().name().equals("read_file"))
                .findFirst().orElseThrow();
        Tool write = BuiltInTools.all().stream()
                .filter(tool -> tool.spec().name().equals("write_file"))
                .findFirst().orElseThrow();
        assertFalse(read.mutatesWorkspace());
        assertTrue(read.concurrencySafe());
        assertTrue(write.mutatesWorkspace());
        assertFalse(write.concurrencySafe());
    }
}
