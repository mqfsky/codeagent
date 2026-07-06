package minicode.context.compact;

import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 上下文压缩后的消息和边界信息。
 *
 * @param messages 压缩或执行后的消息列表
 * @param boundary 压缩边界；为空表示本次没有产生边界
 */
public record CompressionResult(List<ChatMessage> messages, Optional<CompressionBoundaryResult> boundary) {
    public CompressionResult {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        boundary = Objects.requireNonNull(boundary, "boundary");
    }
}
