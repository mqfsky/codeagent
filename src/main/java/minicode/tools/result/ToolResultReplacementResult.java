package minicode.tools.result;

import minicode.core.message.ToolResultMessage;

import java.util.Objects;
import java.util.Optional;

/**
 * 单条工具结果替换后的消息和记录。
 *
 * @param message 结果说明消息
 * @param replacement 替换记录；为空表示未发生替换
 */
public record ToolResultReplacementResult(ToolResultMessage message,
                                          Optional<ToolResultReplacementRecord> replacement) {
    public ToolResultReplacementResult {
        message = Objects.requireNonNull(message, "message");
        replacement = Objects.requireNonNull(replacement, "replacement");
        if (replacement.isPresent()) {
            ToolResultReplacementRecord record = replacement.get();
            if (!message.content().equals(record.replacementContent())) {
                throw new IllegalArgumentException("replacement content must match tool result message content");
            }
        }
    }
}
