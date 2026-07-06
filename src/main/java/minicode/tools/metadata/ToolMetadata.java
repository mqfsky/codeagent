package minicode.tools.metadata;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.Objects;

/**
 * 工具注册和提示词暴露所需的元数据。
 *
 * @param name 名称
 * @param description 描述文本
 * @param inputSchema 工具输入 JSON schema；为空表示没有 schema
 * @param origin 工具来源
 * @param capabilities 工具能力标签集合
 * @param status 工具当前可用状态
 */
public record ToolMetadata(String name, String description, JsonNode inputSchema, ToolOrigin origin,
                           Set<ToolCapability> capabilities, ToolStatus status) {
    public ToolMetadata {
        requireText(name, "name");
        description = Objects.requireNonNull(description, "description");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        origin = Objects.requireNonNull(origin, "origin");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        status = Objects.requireNonNull(status, "status");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
