package minicode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * MCP 工具的描述信息。
 *
 * @param name 名称
 * @param description 描述文本
 * @param inputSchema 工具输入 JSON schema；为空表示没有 schema
 */
public record McpToolDescriptor(String name, String description, Optional<JsonNode> inputSchema) {
    public McpToolDescriptor {
        if (Objects.requireNonNull(name, "name").isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
