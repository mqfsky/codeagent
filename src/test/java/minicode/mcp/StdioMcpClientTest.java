package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StdioMcpClientTest {
    @Test
    void initializesListsToolsAndCallsToolOverNewlineDelimitedStdio() {
        StdioMcpClient client = new StdioMcpClient("Fake Server", fakeConfig("happy"));
        try {
            McpInitialization initialization = client.start();

            List<McpToolDescriptor> tools = client.listTools();
            JsonNode result = client.callTool("Echo Tool", JsonNodeFactory.instance.objectNode().put("value", "hello"));

            assertEquals("2024-11-05", initialization.protocolVersion());
            assertEquals("Use Echo Tool only when echoing user-provided text.", initialization.instructions());
            assertSame(initialization, client.start());
            assertEquals(2, tools.size());
            assertEquals("Echo Tool", tools.getFirst().name());
            assertEquals("object", tools.getFirst().inputSchema().orElseThrow().get("type").asText());
            assertFalse(result.get("isError").asBoolean());
            assertEquals("echo: hello", result.get("content").get(0).get("text").asText());
        } finally {
            client.close();
        }
    }

    @Test
    void mapsToolCallIsErrorThroughFormatter() {
        StdioMcpClient client = new StdioMcpClient("Fake Server", fakeConfig("happy"));
        try {
            client.start();

            JsonNode result = client.callTool("Fail Tool", JsonNodeFactory.instance.objectNode());

            assertTrue(McpToolResultFormatter.toToolResult(result).error());
            assertEquals("remote failed", McpToolResultFormatter.toToolResult(result).content());
        } finally {
            client.close();
        }
    }

    @Test
    void invalidJsonDuringInitializeFailsAsProtocolError() {
        StdioMcpClient client = new StdioMcpClient("Bad Server", fakeConfig("invalid-json"));

        McpException exception = assertThrows(McpException.class, client::start);

        assertEquals(McpErrorKind.PROTOCOL_ERROR, exception.kind());
        client.close();
    }

    @Test
    void trailingGarbageAfterJsonDuringInitializeFailsAsProtocolError() {
        StdioMcpClient client = new StdioMcpClient("Bad Server", fakeConfig("trailing-json"));

        McpException exception = assertThrows(McpException.class, client::start);

        assertEquals(McpErrorKind.PROTOCOL_ERROR, exception.kind());
        client.close();
    }

    @Test
    void mismatchedResponseIdFailsAsProtocolError() {
        StdioMcpClient client = new StdioMcpClient("Bad Server", fakeConfig("id-mismatch"));

        McpException exception = assertThrows(McpException.class, client::start);

        assertEquals(McpErrorKind.PROTOCOL_ERROR, exception.kind());
        assertTrue(exception.getMessage().contains("response id"));
        client.close();
    }

    @Test
    void jsonRpcErrorUsesLifecycleFailureKind() {
        StdioMcpClient client = new StdioMcpClient("Rejecting Server", fakeConfig("initialize-error"));

        McpException exception = assertThrows(McpException.class, client::start);

        assertEquals(McpErrorKind.HANDSHAKE_FAILED, exception.kind());
        assertTrue(exception.getMessage().contains("initialize rejected"));
        client.close();
    }

    @Test
    void acceptsADeclaredNewerNegotiatedProtocolVersion() {
        StdioMcpClient client = new StdioMcpClient("New Server", fakeConfig("new-version"));
        try {
            McpInitialization initialization = client.start();

            assertEquals("2025-11-25", initialization.protocolVersion());
        } finally {
            client.close();
        }
    }

    @Test
    void rejectsAnUnsupportedNegotiatedProtocolVersion() {
        StdioMcpClient client = new StdioMcpClient("Future Server", fakeConfig("unsupported-version"));

        McpException exception = assertThrows(McpException.class, client::start);

        assertEquals(McpErrorKind.HANDSHAKE_FAILED, exception.kind());
        assertTrue(exception.getMessage().contains("unsupported protocol version"));
        client.close();
    }

    @Test
    void initializeTimeoutFailsAndCloseStopsProcess() {
        StdioMcpClient client = new StdioMcpClient("Slow Server", fakeConfig("hang"));

        McpException exception = assertThrows(McpException.class, client::start);

        assertEquals(McpErrorKind.TIMEOUT, exception.kind());
        assertTrue(client.processHandle().isPresent());
        assertFalse(client.processHandle().orElseThrow().isAlive());
        McpException afterTimeout = assertThrows(McpException.class, client::listTools);
        assertEquals(McpErrorKind.HANDSHAKE_FAILED, afterTimeout.kind());
        client.close();
        assertFalse(client.processHandle().orElseThrow().isAlive());
    }

    private static McpServerConfig fakeConfig(String mode) {
        return new McpServerConfig(
                javaCommand(),
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpStdioServer.class.getName(), mode),
                null,
                Map.of(),
                true,
                Duration.ofMillis(800),
                Duration.ofMillis(800)
        );
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
