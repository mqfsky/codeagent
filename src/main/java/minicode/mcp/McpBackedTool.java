package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionContext;
import minicode.permissions.model.PermissionDeniedException;
import minicode.permissions.model.PermissionResource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 将 MCP Server 暴露的远程工具适配为 CodeAgent 内部统一的 {@link Tool}。
 *
 * <p>该适配器根据 {@link McpToolDescriptor} 构造本地工具元数据和输入 Schema，
 * 在执行前按需进行 MCP 工具权限检查，再通过 {@link McpClient} 将调用转发给远端
 * MCP Server，最后把 MCP 协议结果转换为 CodeAgent 的 {@link ToolResult}。</p>
 *
 * <p>本类只负责单个工具的适配和调用，不负责启动或关闭底层 MCP Client；
 * MCP Client 的生命周期由 {@link McpRuntime} 统一管理。</p>
 */
public final class McpBackedTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String serverName;
    private final McpToolDescriptor descriptor;
    private final McpClient client;
    private final Optional<PermissionService> permissionService;
    private final JsonNode inputSchema;
    private final ToolMetadata metadata;

    /**
     * 创建一个不启用权限检查的 MCP 工具适配器。
     *
     * @param serverName MCP Server 名称，用于生成本地工具名和标识工具来源
     * @param descriptor MCP Server 通过 {@code tools/list} 返回的工具描述
     * @param client 负责向对应 MCP Server 发起工具调用的客户端
     */
    public McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client) {
        this(serverName, descriptor, client, Optional.empty());
    }

    /**
     * 创建一个执行前需要经过权限检查的 MCP 工具适配器。
     *
     * @param serverName MCP Server 名称，用于生成本地工具名和标识工具来源
     * @param descriptor MCP Server 通过 {@code tools/list} 返回的工具描述
     * @param client 负责向对应 MCP Server 发起工具调用的客户端
     * @param permissionService MCP 工具权限检查服务
     */
    public McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client,
                         PermissionService permissionService) {
        this(serverName, descriptor, client, Optional.of(permissionService));
    }

    /**
     * 统一完成字段校验、输入 Schema 归一化和本地工具元数据构造。
     *
     * @param serverName MCP Server 名称
     * @param descriptor MCP 工具描述
     * @param client MCP 客户端
     * @param permissionService 可选的权限检查服务
     */
    private McpBackedTool(String serverName, McpToolDescriptor descriptor, McpClient client,
                          Optional<PermissionService> permissionService) {
        this.serverName = requireText(serverName, "serverName");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.client = Objects.requireNonNull(client, "client");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        // MCP Server 未提供合法对象 Schema 时，使用允许任意字段的对象 Schema 兜底。
        this.inputSchema = normalizeInputSchema(descriptor.inputSchema().orElse(null));
        // 将服务名和远端工具名包装为全局唯一的本地工具名，避免多个 MCP Server 之间重名。
        this.metadata = new ToolMetadata(
                McpToolName.wrappedName(serverName, descriptor.name()),
                descriptor.description().isBlank()
                        ? "Call MCP tool " + descriptor.name() + " from server " + serverName + "."
                        : descriptor.description(),
                inputSchema,
                ToolOrigin.MCP,
                Set.of(ToolCapability.COMMAND),
                ToolStatus.AVAILABLE
        );
    }

    /**
     * 返回注册到 CodeAgent 工具表中的 MCP 工具元数据。
     *
     * @return 包含包装后工具名、描述、Schema、来源和状态的元数据
     */
    @Override
    public ToolMetadata metadata() {
        return metadata;
    }

    /**
     * 返回归一化后的工具输入 Schema。
     *
     * @return JSON Object 类型的输入 Schema
     */
    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    /**
     * 校验 MCP 工具输入是否为 JSON Object。
     *
     * <p>缺失值或 {@code null} 会被归一化为空对象；非对象类型会返回校验失败。</p>
     *
     * @param input 模型生成的原始工具输入
     * @return 携带归一化输入或错误信息的校验结果
     */
    @Override
    public ValidationResult validateInput(JsonNode input) {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return ValidationResult.valid(MAPPER.createObjectNode());
        }
        if (!input.isObject()) {
            return ValidationResult.invalid(List.of("MCP tool input must be a JSON object"));
        }
        return ValidationResult.valid(input);
    }

    /**
     * 执行 MCP 工具调用。
     *
     * <p>如果配置了权限服务，会先根据当前 session、turn 和 tool-use 上下文进行权限检查；
     * 用户拒绝时返回错误结果，不会调用远端 MCP Server。权限通过后，将调用转发给原始
     * MCP 工具，并把 MCP 返回内容格式化为 CodeAgent 的统一工具结果。</p>
     *
     * @param normalizedInput 经过 {@link #validateInput(JsonNode)} 归一化的工具参数
     * @param toolContext 当前工具调用的 session、turn 和 tool-use 上下文
     * @return MCP 调用结果，或权限被拒绝时的错误结果
     */
    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
        if (permissionService.isPresent()) {
            try {
                // 在真正访问远端 MCP 工具之前完成权限审批。
                permissionService.orElseThrow().ensureMcpTool(new PermissionResource.McpToolResource(
                        serverName,
                        descriptor.name(),
                        metadata.name(),
                        metadata.description()
                ), new PermissionContext(toolContext.sessionId(), toolContext.turnId(), toolContext.toolUseId()));
            } catch (PermissionDeniedException exception) {
                // 权限拒绝属于可展示的工具错误，不再继续调用 MCP Server。
                String message = exception.feedback()
                        .map(feedback -> "Permission denied: " + feedback)
                        .orElse("Permission denied");
                return ToolResult.error(message);
            }
        }
        // 使用远端原始工具名发起调用，再转换为 CodeAgent 统一的 ToolResult。
        return McpToolResultFormatter.toToolResult(client.callTool(descriptor.name(), normalizedInput));
    }

    /**
     * 返回提供该工具的 MCP Server 名称。
     *
     * @return MCP Server 名称
     */
    public String serverName() {
        return serverName;
    }

    /**
     * 返回 MCP Server 声明的原始工具名称。
     *
     * @return 未经过本地包装的工具名
     */
    public String originalToolName() {
        return descriptor.name();
    }

    /**
     * 将 MCP 工具输入 Schema 归一化为 JSON Object Schema。
     *
     * @param schema MCP Server 返回的可选输入 Schema
     * @return 原始对象 Schema，或允许任意属性的兜底对象 Schema
     */
    private static JsonNode normalizeInputSchema(JsonNode schema) {
        if (schema != null && schema.isObject()) {
            return schema;
        }
        ObjectNode fallback = MAPPER.createObjectNode();
        fallback.put("type", "object");
        fallback.put("additionalProperties", true);
        return fallback;
    }

    /**
     * 校验字符串不为 {@code null} 且不为空白。
     *
     * @param value 待校验字符串
     * @param name 参数名称，用于异常信息
     * @return 校验通过的原始字符串
     * @throws NullPointerException 当字符串为 {@code null} 时抛出
     * @throws IllegalArgumentException 当字符串为空白时抛出
     */
    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
