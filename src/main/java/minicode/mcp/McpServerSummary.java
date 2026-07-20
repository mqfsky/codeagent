package minicode.mcp;

import java.util.Objects;
import java.util.Optional;

/**
 * MCP 服务在系统提示词和 UI 中展示的摘要。
 *
 * @param name 名称
 * @param endpoint stdio 命令或脱敏后的 HTTP endpoint
 * @param status MCP server 当前状态
 * @param toolCount 服务暴露的工具数量
 * @param error 是否表示错误；为空表示该条目没有错误语义
 * @param errorKind 错误类型；为空表示当前没有错误
 * @param instructions Server 在 initialize 结果中返回的使用说明；为空表示未提供
 */
public record McpServerSummary(String name, String endpoint, McpServerStatus status, int toolCount,
                               Optional<String> error, Optional<McpErrorKind> errorKind,
                               Optional<String> instructions) {
    public McpServerSummary(String name, String endpoint, McpServerStatus status, int toolCount,
                            Optional<String> error) {
        this(name, endpoint, status, toolCount, error, Optional.empty(), Optional.empty());
    }

    public McpServerSummary(String name, String endpoint, McpServerStatus status, int toolCount,
                            Optional<String> error, Optional<McpErrorKind> errorKind) {
        this(name, endpoint, status, toolCount, error, errorKind, Optional.empty());
    }

    public McpServerSummary {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        status = Objects.requireNonNull(status, "status");
        if (toolCount < 0) {
            throw new IllegalArgumentException("toolCount must be non-negative");
        }
        error = Objects.requireNonNull(error, "error");
        errorKind = Objects.requireNonNull(errorKind, "errorKind");
        instructions = Objects.requireNonNull(instructions, "instructions")
                .filter(value -> !value.isBlank());
    }
}
