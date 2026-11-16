package io.github.jasoncai0.agent.mcp;

import io.github.jasoncai0.agent.tool.ToolRegistry;
import io.github.jasoncai0.agent.tool.ToolResult;
import io.github.jasoncai0.agent.tool.builtin.BuiltInTools;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStdioIntegrationTest {
    @Test
    void discoversAndCallsStdioTool() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "python3 is required for stdio MCP integration test");
        Path dir = Files.createTempDirectory("jade-mcp-stdio");
        Path server = dir.resolve("server.py");
        Files.writeString(server, """
                import json, sys
                for line in sys.stdin:
                    req = json.loads(line)
                    if "id" not in req:
                        continue
                    method = req.get("method")
                    if method == "initialize":
                        result = {"protocolVersion": "2025-06-18", "capabilities": {},
                                  "serverInfo": {"name": "fake-stdio", "version": "1.0"}}
                    elif method == "tools/list":
                        result = {"tools": [{"name": "echo", "description": "Echo",
                                  "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}}]}
                    elif method == "tools/call":
                        result = {"content": [{"type": "text",
                                  "text": "stdio:" + req["params"]["arguments"].get("text", "?")}]}
                    else:
                        result = {}
                    print(json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": result}), flush=True)
                """);
        McpServerConfig config = new McpServerConfig(
                "stdio", "stdio", "python3", List.of("-u", server.toString()),
                Map.of(), "", Map.of(), 10, true);
        ToolRegistry registry = BuiltInTools.registry();
        McpManager manager = McpManager.connect(new McpConfig(List.of(config)), registry, dir);
        try {
            var tool = registry.find("mcp_stdio_echo");
            assertTrue(tool.isPresent(), "MCP stdio tool should be registered");
            ToolResult result = tool.get().execute(null, Map.of("text", "hello"));
            assertTrue(result.ok(), result.output());
            assertEquals("stdio:hello", result.output());
        } finally {
            manager.close();
        }
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("python3", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
