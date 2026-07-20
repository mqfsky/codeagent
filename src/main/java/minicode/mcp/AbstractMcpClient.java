package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 与具体传输无关的 MCP JSON-RPC 客户端生命周期。
 *
 * <p>子类只负责建立传输、收发完整 JSON-RPC 消息和释放资源；初始化、通知、工具发现、
 * 工具调用以及响应信封校验都在这里保持一致。</p>
 */
public abstract class AbstractMcpClient implements McpClient {
    public static final String PROTOCOL_VERSION_2024_11_05 = "2024-11-05";
    public static final String PROTOCOL_VERSION_2025_03_26 = "2025-03-26";
    public static final String PROTOCOL_VERSION_2025_06_18 = "2025-06-18";
    public static final String PROTOCOL_VERSION_2025_11_25 = "2025-11-25";

    protected static final ObjectMapper JSON = new ObjectMapper();

    private final String serverName;
    private final String requestedProtocolVersion;
    private final Set<String> supportedProtocolVersions;
    private int nextId = 1;
    private McpInitialization initialization;

    protected AbstractMcpClient(String serverName, String requestedProtocolVersion,
                                Set<String> supportedProtocolVersions) {
        this.serverName = requireText(serverName, "serverName");
        this.requestedProtocolVersion = requireText(requestedProtocolVersion, "requestedProtocolVersion");
        this.supportedProtocolVersions = Set.copyOf(
                Objects.requireNonNull(supportedProtocolVersions, "supportedProtocolVersions"));
        if (this.supportedProtocolVersions.isEmpty()) {
            throw new IllegalArgumentException("supportedProtocolVersions must not be empty");
        }
        if (!this.supportedProtocolVersions.contains(this.requestedProtocolVersion)) {
            throw new IllegalArgumentException("requestedProtocolVersion must be supported");
        }
    }

    @Override
    public final synchronized McpInitialization start() {
        if (initialization != null) {
            return initialization;
        }
        openTransport();
        return initializeLifecycle();
    }

    /**
     * 在已有传输上重新执行完整 initialize / initialized 生命周期。
     *
     * <p>Streamable HTTP 子类可在 Server 明确报告 Session 已失效后调用本方法，随后仅重试
     * 那个未被 Server 接受的原始请求一次。initialize 请求自身不应触发递归重建。</p>
     */
    protected final synchronized McpInitialization reinitialize() {
        initialization = null;
        return initializeLifecycle();
    }

    @Override
    public final synchronized List<McpToolDescriptor> listTools() {
        ensureInitialized();
        JsonNode result = request("tools/list", JSON.createObjectNode(), requestTimeout(),
                McpErrorKind.LIST_TOOLS_FAILED);
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode inputSchema = tool.get("inputSchema");
            descriptors.add(new McpToolDescriptor(
                    name,
                    tool.path("description").asText(""),
                    inputSchema == null || inputSchema.isNull() ? Optional.empty() : Optional.of(inputSchema)
            ));
        }
        return List.copyOf(descriptors);
    }

    @Override
    public final synchronized JsonNode callTool(String name, JsonNode arguments) {
        ensureInitialized();
        ObjectNode params = JSON.createObjectNode();
        params.put("name", requireText(name, "name"));
        params.set("arguments", arguments == null || arguments.isNull() || arguments.isMissingNode()
                ? JSON.createObjectNode()
                : arguments);
        return request("tools/call", params, requestTimeout(), McpErrorKind.TOOL_CALL_FAILED);
    }

    protected abstract void openTransport();

    /**
     * 发送一条带 id 的 JSON-RPC 请求并返回完整的 JSON-RPC 响应信封。
     */
    protected abstract JsonNode exchange(ObjectNode message, Duration timeout, String method,
                                         McpErrorKind failureKind);

    /**
     * 发送一条 JSON-RPC notification。允许 HTTP 传输消费并校验对应的空 HTTP 响应。
     */
    protected abstract void sendNotification(ObjectNode message, Duration timeout, String method,
                                             McpErrorKind failureKind);

    protected abstract Duration initializeTimeout();

    protected abstract Duration requestTimeout();

    /**
     * 初始化结果完成校验并写入缓存后、发送 initialized notification 前调用。
     */
    protected void onInitialized(McpInitialization initialized) {
    }

    protected final synchronized Optional<McpInitialization> currentInitialization() {
        return Optional.ofNullable(initialization);
    }

    /**
     * 传输关闭后清除生命周期缓存，使同一客户端实例可以重新启动。
     */
    protected final synchronized void resetInitialization() {
        initialization = null;
    }

    protected final String serverName() {
        return serverName;
    }

    private McpInitialization initializeLifecycle() {
        try {
            JsonNode result = request("initialize", initializeParams(), initializeTimeout(),
                    McpErrorKind.HANDSHAKE_FAILED);
            String protocolVersion = initializeProtocolVersion(result);
            String instructions = initializeInstructions(result);
            McpInitialization initialized = new McpInitialization(protocolVersion, instructions);
            initialization = initialized;
            onInitialized(initialized);
            notify("notifications/initialized", JSON.createObjectNode(), initializeTimeout(),
                    McpErrorKind.HANDSHAKE_FAILED);
            return Objects.requireNonNull(initialization, "initialization");
        } catch (RuntimeException exception) {
            initialization = null;
            throw exception;
        }
    }

    private JsonNode request(String method, JsonNode params, Duration timeout, McpErrorKind failureKind) {
        int id = nextId++;
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);

        JsonNode response = exchange(message, timeout, method, failureKind);
        return validateResponse(response, id, method, failureKind);
    }

    private void notify(String method, JsonNode params, Duration timeout, McpErrorKind failureKind) {
        ObjectNode message = JSON.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);
        sendNotification(message, timeout, method, failureKind);
    }

    private JsonNode validateResponse(JsonNode response, int expectedId, String method,
                                      McpErrorKind failureKind) {
        if (response == null || !response.isObject()) {
            throw protocolError(method, "response must be a JSON object");
        }
        if (!"2.0".equals(response.path("jsonrpc").asText())) {
            throw protocolError(method, "response has an invalid jsonrpc version");
        }
        JsonNode responseId = response.get("id");
        if (responseId == null || !responseId.isIntegralNumber() || responseId.longValue() != expectedId) {
            throw protocolError(method, "response id does not match request id " + expectedId);
        }

        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText("request failed");
            JsonNode code = error.get("code");
            String codeText = code == null || code.isNull() ? "" : " (code " + code.asText() + ")";
            throw new McpException(failureKind, "MCP " + serverName + ": " + message + codeText);
        }
        if (!response.has("result")) {
            throw protocolError(method, "response has neither result nor error");
        }
        return response.get("result");
    }

    private ObjectNode initializeParams() {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", requestedProtocolVersion);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "minicode-java").put("version", "0.1.0");
        return params;
    }

    private String initializeProtocolVersion(JsonNode result) {
        if (result == null || !result.isObject()) {
            throw protocolError("initialize", "result must be a JSON object");
        }
        JsonNode versionNode = result.get("protocolVersion");
        if (versionNode == null || !versionNode.isTextual() || versionNode.asText().isBlank()) {
            throw protocolError("initialize", "result is missing protocolVersion");
        }
        String protocolVersion = versionNode.asText();
        if (!supportedProtocolVersions.contains(protocolVersion)) {
            throw new McpException(McpErrorKind.HANDSHAKE_FAILED,
                    "MCP " + serverName + ": unsupported protocol version " + protocolVersion + ".");
        }
        return protocolVersion;
    }

    private String initializeInstructions(JsonNode result) {
        JsonNode instructions = result.get("instructions");
        if (instructions == null || instructions.isNull()) {
            return "";
        }
        if (!instructions.isTextual()) {
            throw protocolError("initialize", "instructions must be a string");
        }
        return instructions.asText();
    }

    private void ensureInitialized() {
        if (initialization == null) {
            throw new McpException(McpErrorKind.HANDSHAKE_FAILED,
                    "MCP server \"" + serverName + "\" has not been initialized.");
        }
    }

    private McpException protocolError(String method, String detail) {
        return new McpException(McpErrorKind.PROTOCOL_ERROR,
                "MCP " + serverName + ": invalid response for " + method + ": " + detail + ".");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
