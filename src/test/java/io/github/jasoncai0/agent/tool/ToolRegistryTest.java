package io.github.jasoncai0.agent.tool;

import io.github.jasoncai0.agent.llm.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    @TempDir
    Path temp;

    @Test
    void registersExecutesAndRejectsDuplicateNames() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        ToolResult result = registry.execute("echo", Map.of("text", "hello"), null);

        assertTrue(result.ok());
        assertEquals("hello", result.output());
        assertTrue(registry.names().contains("echo"));
        assertTrue(registry.describe().get(0).containsKey("parameters"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoTool()));
    }

    @Test
    void discoversServiceLoaderProvidersAndRejectsConflicts() throws Exception {
        Path services = temp.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(ToolProvider.class.getName()),
                EchoProvider.class.getName() + "\n");

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{temp.toUri().toURL()}, getClass().getClassLoader())) {
            ToolRegistry discovered = new ToolRegistry();
            discovered.discover(loader);

            assertTrue(discovered.names().contains("echo_provider"));
            assertEquals("provider:hello",
                    discovered.execute("echo_provider", Map.of("text", "hello"), null).output());

            ToolRegistry conflict = new ToolRegistry();
            conflict.register(new EchoProviderTool());
            assertThrows(IllegalArgumentException.class, () -> conflict.discover(loader));
        }
    }

    public static final class EchoTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("echo", "Echo text.",
                    Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))));
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            return ToolResult.ok(String.valueOf(args.get("text")));
        }
    }

    public static final class EchoProviderTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("echo_provider", "Echo from a provider.",
                    Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))));
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            return ToolResult.ok("provider:" + args.get("text"));
        }
    }

    public static final class EchoProvider implements ToolProvider {
        @Override
        public List<Tool> tools() {
            return List.of(new EchoProviderTool());
        }
    }
}
