package minicode.mcp;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 根据 MCP Server 配置选择 CodeAgent 自研的传输客户端。
 */
public final class McpClientFactory {
    private McpClientFactory() {
    }

    public static McpClient create(String serverName, McpServerConfig config, Path baseCwd) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(baseCwd, "baseCwd");
        return switch (config.transportKind()) {
            case STDIO -> new StdioMcpClient(serverName, config, baseCwd);
            case STREAMABLE_HTTP -> new StreamableHttpMcpClient(serverName, config);
            case INVALID -> throw new McpException(
                    McpErrorKind.START_FAILED,
                    "MCP server \"" + serverName
                            + "\" must configure exactly one valid command or absolute http/https url."
            );
        };
    }
}
