package minicode.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class McpRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hydratesWorkingServersAndSummarizesStartFailuresWithoutBlockingOthers() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        configs.put("Good Server", fakeConfig("happy"));
        configs.put("Missing Server", new McpServerConfig(
                "definitely-not-a-command-for-minicode-mcp-test",
                List.of(),
                null,
                Map.of(),
                true,
                Duration.ofMillis(500),
                Duration.ofMillis(500)
        ));

        McpRuntime runtime = McpToolHydrator.hydrate(configs);
        try {
            assertEquals(2, runtime.summaries().size());
            assertEquals(McpServerStatus.CONNECTED, runtime.summaries().get(0).status());
            assertEquals(2, runtime.tools().size());
            assertEquals(McpServerStatus.ERROR, runtime.summaries().get(1).status());
            assertEquals(McpErrorKind.START_FAILED, runtime.summaries().get(1).errorKind().orElseThrow());
        } finally {
            runtime.close();
        }
    }

    @Test
    void isolatesInvalidEnabledEndpointsButLeavesInvalidDisabledServersDisabled() {
        Map<String, McpServerConfig> configs = new LinkedHashMap<>();
        configs.put("Good Server", fakeConfig("happy"));
        configs.put("Invalid Server", new McpServerConfig(
                "", List.of(), null, Map.of(), "ftp://example.com/private?token=hidden", Map.of(), true,
                Duration.ofMillis(500), Duration.ofMillis(500)));
        configs.put("Disabled Server", new McpServerConfig(
                "", List.of(), null, Map.of(), null, Map.of(), false,
                Duration.ofMillis(500), Duration.ofMillis(500)));

        McpRuntime runtime = McpToolHydrator.hydrate(configs);
        try {
            assertEquals(McpServerStatus.CONNECTED, runtime.summaries().get(0).status());
            assertEquals(McpServerStatus.ERROR, runtime.summaries().get(1).status());
            assertEquals(McpErrorKind.START_FAILED, runtime.summaries().get(1).errorKind().orElseThrow());
            assertFalse(runtime.summaries().get(1).error().orElseThrow().contains("hidden"));
            assertEquals("invalid MCP endpoint configuration", runtime.summaries().get(1).endpoint());
            assertEquals(McpServerStatus.DISABLED, runtime.summaries().get(2).status());
        } finally {
            runtime.close();
        }
    }

    @Test
    void hydratedToolsCanBeRegisteredAndExecutedThroughToolRegistry() {
        McpRuntime runtime = McpToolHydrator.hydrate(Map.of("Good Server", fakeConfig("happy")));
        try {
            ToolRegistry registry = new ToolRegistry();
            runtime.tools().forEach(registry::register);

            ToolResult result = registry.execute(
                    new ToolCall("tool-use-1", "mcp__good_server__echo_tool", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("value", "ok")),
                    new ToolContext(Path.of("."), "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"))
            );

            assertFalse(result.error());
            assertTrue(result.content().contains("echo: ok"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void hydratesStdioAndHttpTogetherAndKeepsInstructionsPerConnectedServer() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> {
                ObjectNode initialize = JSON.createObjectNode();
                initialize.put("protocolVersion", AbstractMcpClient.PROTOCOL_VERSION_2025_11_25);
                initialize.putObject("capabilities");
                initialize.putObject("serverInfo").put("name", "runtime-http").put("version", "1.0");
                initialize.put("instructions", "Use the HTTP echo tool for remote values.");
                return FakeMcpHttpServer.Response.jsonResult(request, initialize);
            });
            server.enqueue(FakeMcpHttpServer.Response.empty(202));
            server.enqueue(request -> {
                ObjectNode result = JSON.createObjectNode();
                result.putArray("tools").addObject()
                        .put("name", "http_echo")
                        .put("description", "Echo over HTTP")
                        .putObject("inputSchema").put("type", "object");
                return FakeMcpHttpServer.Response.jsonResult(request, result);
            });

            Map<String, McpServerConfig> configs = new LinkedHashMap<>();
            configs.put("stdio", fakeConfig("happy"));
            configs.put("http", new McpServerConfig(
                    "", List.of(), null, Map.of(), server.endpoint("/mcp").toString(), Map.of(), true,
                    Duration.ofSeconds(2), Duration.ofSeconds(2)));

            McpRuntime runtime = McpToolHydrator.hydrate(configs);
            try {
                assertEquals(2, runtime.summaries().size());
                assertTrue(runtime.summaries().stream()
                        .allMatch(summary -> summary.status() == McpServerStatus.CONNECTED));
                assertEquals(3, runtime.tools().size());
                assertEquals("Use Echo Tool only when echoing user-provided text.",
                        runtime.summaries().get(0).instructions().orElseThrow());
                assertEquals("Use the HTTP echo tool for remote values.",
                        runtime.summaries().get(1).instructions().orElseThrow());
                assertEquals("http://" + server.endpoint("/mcp").getAuthority() + "/mcp",
                        runtime.summaries().get(1).endpoint());
            } finally {
                runtime.close();
            }
            server.assertHealthy();
        }
    }

    private static McpServerConfig fakeConfig(String mode) {
        return new McpServerConfig(
                Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpStdioServer.class.getName(), mode),
                null,
                Map.of(),
                true,
                Duration.ofMillis(800),
                Duration.ofMillis(800)
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
