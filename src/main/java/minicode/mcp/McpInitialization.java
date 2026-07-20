package minicode.mcp;

import java.util.Objects;

/**
 * MCP 初始化握手协商出的运行时信息。
 *
 * @param protocolVersion Server 选择的 MCP 协议版本
 * @param instructions Server 提供的可选使用说明；未提供时为空字符串
 */
public record McpInitialization(String protocolVersion, String instructions) {
    public McpInitialization {
        protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion").trim();
        if (protocolVersion.isEmpty()) {
            throw new IllegalArgumentException("protocolVersion must not be blank");
        }
        instructions = instructions == null ? "" : instructions;
    }
}
