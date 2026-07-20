package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.mcp.FakeMcpHttpServer.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamableHttpMcpClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exchangesJsonAndPropagatesSessionProtocolAndCustomHeaders() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25",
                    "Prefer the remote search tool.", "session-one"));
            server.enqueue(Response.empty(202));
            server.enqueue(request -> Response.jsonResult(request, toolsResult()));
            server.enqueue(request -> Response.jsonResult(request, callResult("echo: hello")));
            server.enqueue(Response.empty(405));

            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "remote", config(server, "/mcp?tenant=demo", Map.of("Authorization", "Bearer test-token")));
            McpInitialization initialization = client.start();
            List<McpToolDescriptor> tools = client.listTools();
            JsonNode call = client.callTool("echo", JSON.createObjectNode().put("value", "hello"));
            client.close();

            assertEquals("2025-11-25", initialization.protocolVersion());
            assertEquals("Prefer the remote search tool.", initialization.instructions());
            assertEquals(List.of("echo"), tools.stream().map(McpToolDescriptor::name).toList());
            assertEquals("echo: hello", call.path("content").get(0).path("text").asText());

            List<FakeMcpHttpServer.RecordedRequest> requests = server.requests();
            assertEquals(5, requests.size());
            assertEquals("POST", requests.get(0).method());
            assertEquals("/mcp", requests.get(0).uri().getPath());
            assertEquals("tenant=demo", requests.get(0).uri().getRawQuery());
            assertEquals("application/json", requests.get(0).header("Content-Type"));
            assertEquals("application/json, text/event-stream", requests.get(0).header("Accept"));
            assertEquals("Bearer test-token", requests.get(0).header("Authorization"));
            assertNull(requests.get(0).header("MCP-Session-Id"));
            assertNull(requests.get(0).header("MCP-Protocol-Version"));

            assertEquals("notifications/initialized", requests.get(1).rpcMethod());
            assertEquals("session-one", requests.get(1).header("MCP-Session-Id"));
            assertEquals("2025-11-25", requests.get(1).header("MCP-Protocol-Version"));
            assertEquals("tools/list", requests.get(2).rpcMethod());
            assertEquals("session-one", requests.get(2).header("MCP-Session-Id"));
            assertEquals("tools/call", requests.get(3).rpcMethod());

            assertEquals("DELETE", requests.get(4).method());
            assertEquals("session-one", requests.get(4).header("MCP-Session-Id"));
            assertEquals("2025-11-25", requests.get(4).header("MCP-Protocol-Version"));
            assertEquals("Bearer test-token", requests.get(4).header("Authorization"));
            server.assertHealthy();
        }
    }

    @Test
    void readsPostSseUntilMatchingResponseWithoutWaitingForStreamClose() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "", null));
            server.enqueue(Response.empty(204));
            server.enqueue(request -> {
                String id = request.json().get("id").toString();
                String sse = ": keep-alive\n\n"
                        + "data:\n\n"
                        + "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}\n\n"
                        + "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\n"
                        + "data: \"id\":" + id + ",\"result\":" + toolsResult() + "}\n\n";
                return Response.text(200, "text/event-stream; charset=utf-8", sse)
                        .asChunked()
                        .afterFlush(Duration.ofSeconds(2));
            });

            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "sse", config(server, "/mcp", Map.of()));
            client.start();
            long started = System.nanoTime();
            List<McpToolDescriptor> tools = client.listTools();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            client.close();

            assertEquals(List.of("echo"), tools.stream().map(McpToolDescriptor::name).toList());
            assertTrue(elapsedMillis < 1_000,
                    () -> "SSE matching response should complete before stream close, elapsed=" + elapsedMillis);
            server.assertHealthy();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2025-06-18", "2025-03-26"})
    void acceptsSupportedStreamableHttpProtocolVersions(String protocolVersion) {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, protocolVersion, "", null));
            server.enqueue(Response.empty(202));
            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "versions", config(server, "/mcp", Map.of()));

            McpInitialization initialization = client.start();
            client.close();

            assertEquals(protocolVersion, initialization.protocolVersion());
            assertEquals(protocolVersion,
                    server.requests().get(1).header("MCP-Protocol-Version"));
            assertNull(server.requests().get(1).header("MCP-Session-Id"));
            server.assertHealthy();
        }
    }

    @Test
    void rejectsLegacyHttpSseProtocolVersion() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2024-11-05", "", null));
            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "legacy", config(server, "/mcp", Map.of()));

            McpException exception = assertThrows(McpException.class, client::start);
            client.close();

            assertEquals(McpErrorKind.HANDSHAKE_FAILED, exception.kind());
            assertEquals(1, server.requests().size());
            server.assertHealthy();
        }
    }

    @Test
    void rebuildsExpiredSessionAndRetriesOriginalRequestOnce() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "old", "old-session"));
            server.enqueue(Response.empty(202));
            server.enqueue(Response.text(404, "text/plain", "expired"));
            server.enqueue(request -> initializeResponse(request, "2025-06-18", "new", "new-session"));
            server.enqueue(Response.empty(204));
            server.enqueue(request -> Response.jsonResult(request, toolsResult()));
            server.enqueue(Response.empty(200));

            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "sessions", config(server, "/mcp", Map.of()));
            client.start();
            List<McpToolDescriptor> tools = client.listTools();
            McpInitialization refreshed = client.start();
            client.close();

            assertEquals(1, tools.size());
            assertEquals("2025-06-18", refreshed.protocolVersion());
            assertEquals("new", refreshed.instructions());
            List<FakeMcpHttpServer.RecordedRequest> requests = server.requests();
            assertEquals(7, requests.size());
            assertEquals("old-session", requests.get(2).header("MCP-Session-Id"));
            assertEquals("initialize", requests.get(3).rpcMethod());
            assertNull(requests.get(3).header("MCP-Session-Id"));
            assertNull(requests.get(3).header("MCP-Protocol-Version"));
            assertEquals("new-session", requests.get(4).header("MCP-Session-Id"));
            assertEquals("2025-06-18", requests.get(4).header("MCP-Protocol-Version"));
            assertEquals("tools/list", requests.get(5).rpcMethod());
            assertEquals("new-session", requests.get(5).header("MCP-Session-Id"));
            assertEquals("2025-06-18", requests.get(5).header("MCP-Protocol-Version"));
            assertEquals("DELETE", requests.get(6).method());
            server.assertHealthy();
        }
    }

    @Test
    void rebuildsSessionWhenInitializedNotificationReturnsNotFoundWithoutRecursing() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25",
                    "stale instructions", "stale-session"));
            server.enqueue(Response.empty(404));
            server.enqueue(request -> initializeResponse(request, "2025-06-18",
                    "fresh instructions", "fresh-session"));
            server.enqueue(Response.empty(202));
            server.enqueue(Response.empty(200));

            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "notification-session", config(server, "/mcp", Map.of()));
            McpInitialization initialization = client.start();
            client.close();

            assertEquals("2025-06-18", initialization.protocolVersion());
            assertEquals("fresh instructions", initialization.instructions());
            List<FakeMcpHttpServer.RecordedRequest> requests = server.requests();
            assertEquals(5, requests.size());
            assertEquals("stale-session", requests.get(1).header("MCP-Session-Id"));
            assertEquals("initialize", requests.get(2).rpcMethod());
            assertNull(requests.get(2).header("MCP-Session-Id"));
            assertEquals("fresh-session", requests.get(3).header("MCP-Session-Id"));
            assertEquals("DELETE", requests.get(4).method());
            server.assertHealthy();
        }
    }

    @Test
    void doesNotRetryAgainWhenRequestFailsAfterSessionReinitialization() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "", "old-session"));
            server.enqueue(Response.empty(202));
            server.enqueue(Response.empty(404));
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "", "new-session"));
            server.enqueue(Response.empty(202));
            server.enqueue(Response.empty(404));
            server.enqueue(Response.empty(404));

            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "retry-once", config(server, "/mcp", Map.of()));
            client.start();
            McpException exception = assertThrows(McpException.class, client::listTools);
            client.close();

            assertEquals(McpErrorKind.REQUEST_FAILED, exception.kind());
            assertEquals(2, server.requests().stream()
                    .filter(request -> "initialize".equals(request.rpcMethod())).count());
            assertEquals(2, server.requests().stream()
                    .filter(request -> "tools/list".equals(request.rpcMethod())).count());
            server.assertHealthy();
        }
    }

    @Test
    void doesNotRetryNonSessionOrServerErrors() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "", null));
            server.enqueue(Response.empty(202));
            server.enqueue(Response.text(500, "text/plain", "do not expose me"));

            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "no-retry", config(server, "/mcp", Map.of()));
            client.start();
            McpException exception = assertThrows(McpException.class, client::listTools);
            client.close();

            assertEquals(McpErrorKind.REQUEST_FAILED, exception.kind());
            assertFalse(exception.getMessage().contains("do not expose me"));
            assertEquals(3, server.requests().size());
            server.assertHealthy();
        }
    }

    @Test
    void rejectsRedirectInsteadOfFollowingIt() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(Response.empty(307)
                    .withHeader("Location", server.endpoint("/redirect-target").toString()));
            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "redirect", config(server, "/mcp", Map.of()));

            McpException exception = assertThrows(McpException.class, client::start);
            client.close();

            assertEquals(McpErrorKind.REQUEST_FAILED, exception.kind());
            assertEquals(1, server.requests().size());
            assertEquals("/mcp", server.requests().getFirst().uri().getPath());
            server.assertHealthy();
        }
    }

    @Test
    void mapsInvalidMediaTypeJsonAndSseToProtocolError() {
        assertInitializeProtocolError(Response.text(200, "text/plain", "{}"));
        assertInitializeProtocolError(Response.json("{not-json"));
        assertInitializeProtocolError(Response.text(200, "text/event-stream", "data: not-json\n\n"));
        assertInitializeProtocolError(Response.text(200, "text/event-stream",
                "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n"));
    }

    @Test
    void mapsHttpTimeoutToTimeoutError() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "", null)
                    .beforeHeaders(Duration.ofMillis(500)));
            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "slow", config(server, "/mcp", Map.of(), Duration.ofMillis(100)));

            McpException exception = assertThrows(McpException.class, client::start);
            client.close();

            assertEquals(McpErrorKind.TIMEOUT, exception.kind());
        }
    }

    @Test
    void rejectsMalformedSessionIdentifierAsProtocolErrorWithoutEchoingIt() {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(request -> initializeResponse(request, "2025-11-25", "", "bad session"));
            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "bad-session", config(server, "/mcp", Map.of()));

            McpException exception = assertThrows(McpException.class, client::start);
            client.close();

            assertEquals(McpErrorKind.PROTOCOL_ERROR, exception.kind());
            assertFalse(exception.getMessage().contains("bad session"));
            assertEquals(1, server.requests().size());
            server.assertHealthy();
        }
    }

    @Test
    void rejectsInvalidEndpointReservedHeadersAndUnexpandedSecretsWithoutLeakingValues() {
        McpServerConfig invalidEndpoint = new McpServerConfig(
                "", List.of(), null, Map.of(), "ftp://example.com/mcp", Map.of(), true,
                Duration.ofMillis(500), Duration.ofMillis(500));
        McpException endpointError = assertThrows(McpException.class,
                () -> new StreamableHttpMcpClient("invalid", invalidEndpoint).start());
        assertEquals(McpErrorKind.START_FAILED, endpointError.kind());

        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            McpException reserved = assertThrows(McpException.class,
                    () -> new StreamableHttpMcpClient("reserved",
                            config(server, "/mcp", Map.of("Accept", "super-secret-value"))).start());
            McpException connection = assertThrows(McpException.class,
                    () -> new StreamableHttpMcpClient("connection",
                            config(server, "/mcp", Map.of("Connection", "connection-secret"))).start());
            McpException transferEncoding = assertThrows(McpException.class,
                    () -> new StreamableHttpMcpClient("transfer-encoding",
                            config(server, "/mcp", Map.of("Transfer-Encoding", "transfer-secret"))).start());
            McpException unresolved = assertThrows(McpException.class,
                    () -> new StreamableHttpMcpClient("unresolved",
                            config(server, "/mcp", Map.of("Authorization", "Bearer ${MISSING}"))).start());

            assertEquals(McpErrorKind.START_FAILED, reserved.kind());
            assertEquals(McpErrorKind.START_FAILED, connection.kind());
            assertEquals(McpErrorKind.START_FAILED, transferEncoding.kind());
            assertEquals(McpErrorKind.START_FAILED, unresolved.kind());
            assertFalse(reserved.getMessage().contains("super-secret-value"));
            assertFalse(connection.getMessage().contains("connection-secret"));
            assertFalse(transferEncoding.getMessage().contains("transfer-secret"));
            assertFalse(unresolved.getMessage().contains("Bearer ${MISSING}"));
            assertEquals(0, server.requests().size());
            server.assertHealthy();
        }
    }

    private static void assertInitializeProtocolError(Response response) {
        try (FakeMcpHttpServer server = new FakeMcpHttpServer()) {
            server.enqueue(response);
            StreamableHttpMcpClient client = new StreamableHttpMcpClient(
                    "protocol", config(server, "/mcp", Map.of()));

            McpException exception = assertThrows(McpException.class, client::start);
            client.close();

            assertEquals(McpErrorKind.PROTOCOL_ERROR, exception.kind());
            server.assertHealthy();
        }
    }

    private static Response initializeResponse(FakeMcpHttpServer.RecordedRequest request,
                                               String protocolVersion, String instructions,
                                               String sessionId) {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", protocolVersion);
        result.putObject("capabilities");
        result.putObject("serverInfo").put("name", "fake-http").put("version", "1.0");
        if (instructions != null && !instructions.isEmpty()) {
            result.put("instructions", instructions);
        }
        Response response = Response.jsonResult(request, result);
        return sessionId == null ? response : response.withHeader("MCP-Session-Id", sessionId);
    }

    private static ObjectNode toolsResult() {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode tool = result.putArray("tools").addObject();
        tool.put("name", "echo");
        tool.put("description", "Echo input");
        tool.putObject("inputSchema").put("type", "object");
        return result;
    }

    private static ObjectNode callResult(String text) {
        ObjectNode result = JSON.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", false);
        return result;
    }

    private static McpServerConfig config(FakeMcpHttpServer server, String path,
                                          Map<String, String> headers) {
        return config(server, path, headers, Duration.ofSeconds(2));
    }

    private static McpServerConfig config(FakeMcpHttpServer server, String path,
                                          Map<String, String> headers, Duration timeout) {
        return new McpServerConfig(
                "", List.of(), null, Map.of(), server.endpoint(path).toString(), headers, true,
                timeout, timeout);
    }
}
