package io.github.jasoncai0.agent.tool.builtin;

import io.github.jasoncai0.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInToolsTest {
    @Test
    void exposesFifteenUniqueTools() {
        List<Tool> tools = BuiltInTools.all();
        assertEquals(15, tools.size());
        Set<String> names = tools.stream().map(tool -> tool.spec().name()).collect(Collectors.toSet());
        assertEquals(tools.size(), names.size());
    }

    @Test
    void includesTheToolOutputReader() {
        assertTrue(BuiltInTools.all().stream()
                .anyMatch(tool -> tool.spec().name().equals("read_tool_output")));
    }
}
