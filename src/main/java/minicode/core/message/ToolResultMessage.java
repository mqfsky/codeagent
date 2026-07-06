package minicode.core.message;

import java.util.Objects;

/**
 * 工具执行完成后回填给模型的结果消息。
 *
 * <p>该消息通过 {@code toolUseId} 与前面的工具调用配对，让模型能基于工具输出继续推理。</p>
 *
 * @param toolUseId 对应的工具调用 id
 * @param toolName 执行的工具名称
 * @param content 工具返回的文本内容
 * @param error 是否表示工具执行失败
 */
public record ToolResultMessage(String toolUseId, String toolName, String content, boolean error) implements ChatMessage {
    public ToolResultMessage {
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        content = Objects.requireNonNull(content, "content");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
