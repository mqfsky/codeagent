package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.service.PromptingPermissionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpBackedToolTest {
    @Test
    void exposesWrappedMetadataAndFallbackSchema() {
        McpToolDescriptor descriptor = new McpToolDescriptor("No Schema!", "No schema tool.", Optional.empty());
        McpBackedTool tool = new McpBackedTool("Server A", descriptor, new StaticMcpClient(result("ok", false)));

        assertEquals("mcp__server_a__no_schema", tool.metadata().name());
        assertEquals(ToolOrigin.MCP, tool.metadata().origin());
        assertEquals("object", tool.inputSchema().get("type").asText());
        assertTrue(tool.inputSchema().get("additionalProperties").asBoolean());
    }

    @Test
    void validatesMissingNullAndObjectInputAsObject() {
        McpBackedTool tool = new McpBackedTool("s", descriptor(), new StaticMcpClient(result("ok", false)));

        assertTrue(tool.validateInput(null).valid());
        assertTrue(tool.validateInput(JsonNodeFactory.instance.nullNode()).valid());
        assertTrue(tool.validateInput(JsonNodeFactory.instance.objectNode().put("a", 1)).valid());
        assertFalse(tool.validateInput(JsonNodeFactory.instance.textNode("bad")).valid());
    }

    @Test
    void registryExecutesWrappedToolSuccessfully() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new McpBackedTool("Server A", descriptor(), new StaticMcpClient(result("remote ok", false))));

        ToolResult result = registry.execute(
                new ToolCall("tool-use-1", "mcp__server_a__echo_tool", JsonNodeFactory.instance.objectNode().put("value", "hello")),
                new ToolContext(Path.of("."), "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"))
        );

        assertFalse(result.error());
        assertEquals("remote ok", result.content());
    }

    @Test
    void deniedMcpPermissionDoesNotReachTheRemoteClient() {
        StaticMcpClient client = new StaticMcpClient(result("must not run", false));
        McpBackedTool tool = new McpBackedTool(
                "Server A",
                descriptor(),
                client,
                new PromptingPermissionService(request -> PermissionPromptResult.deny(
                        "deny_once", PermissionDecision.DENY_ONCE, null))
        );

        ToolResult denied = tool.run(
                JsonNodeFactory.instance.objectNode().put("value", "hello"),
                new ToolContext(Path.of("."), "session-1", Optional.of("turn-1"), Optional.of("tool-use-1"))
        );

        assertTrue(denied.error());
        assertTrue(denied.content().contains("Permission denied"));
        assertEquals(0, client.callCount());
    }

    private static McpToolDescriptor descriptor() {
        return new McpToolDescriptor("Echo Tool", "Echoes.", Optional.of(JsonNodeFactory.instance.objectNode().put("type", "object")));
    }

    private static JsonNode result(String text, boolean error) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("isError", error);
        root.putArray("content").addObject().put("type", "text").put("text", text);
        return root;
    }

    /**
     * 测试用的静态 MCP client。
     *
     * @param result 执行结果；为空表示未完成或未产生结果
     */
    private record StaticMcpClient(JsonNode result, AtomicInteger calls) implements McpClient {
        private StaticMcpClient(JsonNode result) {
            this(result, new AtomicInteger());
        }

        @Override
        public McpInitialization start() {
            return new McpInitialization(AbstractMcpClient.PROTOCOL_VERSION_2024_11_05, "");
        }

        @Override
        public java.util.List<McpToolDescriptor> listTools() {
            return java.util.List.of();
        }

        @Override
        public JsonNode callTool(String name, JsonNode arguments) {
            calls.incrementAndGet();
            return result;
        }

        private int callCount() {
            return calls.get();
        }

        @Override
        public void close() {
        }
    }
}
