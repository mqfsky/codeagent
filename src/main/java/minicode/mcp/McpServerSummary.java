package minicode.mcp;

import java.util.Objects;
import java.util.Optional;

/**
 * MCP 服务在系统提示词和 UI 中展示的摘要。
 *
 * @param name 名称
 * @param command 命令字符串
 * @param status MCP server 当前状态
 * @param toolCount 服务暴露的工具数量
 * @param error 是否表示错误；为空表示该条目没有错误语义
 * @param errorKind 错误类型；为空表示当前没有错误
 */
public record McpServerSummary(String name, String command, McpServerStatus status, int toolCount,
                               Optional<String> error, Optional<McpErrorKind> errorKind) {
    public McpServerSummary(String name, String command, McpServerStatus status, int toolCount,
                            Optional<String> error) {
        this(name, command, status, toolCount, error, Optional.empty());
    }

    public McpServerSummary {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        command = Objects.requireNonNull(command, "command");
        status = Objects.requireNonNull(status, "status");
        if (toolCount < 0) {
            throw new IllegalArgumentException("toolCount must be non-negative");
        }
        error = Objects.requireNonNull(error, "error");
        errorKind = Objects.requireNonNull(errorKind, "errorKind");
    }
}
