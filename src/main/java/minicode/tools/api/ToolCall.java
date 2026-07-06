package minicode.tools.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 模型请求执行的一次工具调用。
 *
 * @param id 唯一标识
 * @param toolName 要执行的工具名称
 * @param input 输入框状态
 */
public record ToolCall(String id, String toolName, JsonNode input) {
    public ToolCall {
        requireText(id, "id");
        requireText(toolName, "toolName");
        input = Objects.requireNonNull(input, "input");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
