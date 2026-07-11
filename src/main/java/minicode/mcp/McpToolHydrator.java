package minicode.mcp;

import minicode.permissions.api.PermissionService;
import minicode.tools.api.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.Path;

public final class McpToolHydrator {
    private McpToolHydrator() {
    }

    public static McpRuntime hydrate(Map<String, McpServerConfig> configs) {
        return hydrate(configs, null);
    }

    public static McpRuntime hydrate(Map<String, McpServerConfig> configs, PermissionService permissionService) {
        return hydrate(configs, permissionService, Path.of(".").toAbsolutePath().normalize());
    }

    /**
     * 根据 MCP Server 配置创建可供 CodeAgent 使用的 MCP 运行时。
     * 返回包装好的 MCP工具，MCP Client，以及配置 summerize
     *
     * <p>该方法会遍历所有 Server 配置：跳过并记录被禁用的 Server；为启用的 Server
     * 创建 {@link StdioMcpClient}、完成初始化握手并读取工具列表，再将每个远端工具包装为
     * {@link McpBackedTool}。当提供 {@code permissionService} 时，包装后的工具会在远端调用前
     * 执行权限检查。</p>
     *
     * <p>单个 Server 启动或工具发现失败时，会关闭对应客户端并把错误记录到
     * {@link McpServerSummary}，不会阻止后续 Server 继续初始化。返回的 {@link McpRuntime}
     * 汇总所有成功加载的工具、Server 状态和仍需维护的客户端；调用方应在应用退出时调用
     * {@link McpRuntime#close()} 释放这些客户端。</p>
     *
     * @param configs MCP Server 名称到启动配置的映射
     * @param permissionService MCP 工具权限服务；为 {@code null} 时不对 MCP 工具执行权限检查
     * @param baseCwd MCP Server 相对工作目录的解析基准
     * @return 包含已加载工具、各 Server 初始化摘要和活动客户端的 MCP 运行时
     * @throws NullPointerException 当 {@code configs} 或 {@code baseCwd} 为 {@code null} 时抛出
     */
    public static McpRuntime hydrate(Map<String, McpServerConfig> configs, PermissionService permissionService,
                                     Path baseCwd) {
        List<Tool> tools = new ArrayList<>();
        // 调用 mcp 的摘要
        List<McpServerSummary> summaries = new ArrayList<>();
        List<McpClient> clients = new ArrayList<>();
        Path actualBaseCwd = Objects.requireNonNull(baseCwd, "baseCwd").toAbsolutePath().normalize();

        // 遍历每个 mcpserever
        for (Map.Entry<String, McpServerConfig> entry : Objects.requireNonNull(configs, "configs").entrySet()) {
            String serverName = entry.getKey();
            McpServerConfig config = entry.getValue();
            // mcp 指令，例如：npx -y @modelcontextprotocol/server-filesystem /tmp
            String command = summarizeCommand(config);
            if (!config.enabled()) {
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.DISABLED, 0, Optional.empty()));
                continue;
            }

            StdioMcpClient client = new StdioMcpClient(serverName, config, actualBaseCwd);
            try {
                // 与 MCP服务器通信，进行初始化
                client.start();
                // 读取 MCP 服务器的工具列表
                List<McpToolDescriptor> descriptors = client.listTools();
                for (McpToolDescriptor descriptor : descriptors) {
                    tools.add(permissionService == null
                            ? new McpBackedTool(serverName, descriptor, client)
                            : new McpBackedTool(serverName, descriptor, client, permissionService));
                }
                clients.add(client);
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.CONNECTED,
                        descriptors.size(), Optional.empty()));
            } catch (RuntimeException exception) {
                client.close();
                McpErrorKind kind = exception instanceof McpException mcpException
                        ? mcpException.kind()
                        : McpErrorKind.TOOL_CALL_FAILED;
                summaries.add(new McpServerSummary(serverName, command, McpServerStatus.ERROR, 0,
                        Optional.of(messageOrDefault(exception)), Optional.of(kind)));
            }
        }
        return new McpRuntime(tools, summaries, clients);
    }

    private static String summarizeCommand(McpServerConfig config) {
        return config.endpointSummary();
    }

    private static String messageOrDefault(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
