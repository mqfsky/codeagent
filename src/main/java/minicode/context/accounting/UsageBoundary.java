package minicode.context.accounting;

import java.util.Objects;
import java.util.Optional;

/**
 * token 用量归属的消息边界。
 *
 * @param messageIndex 消息在列表中的索引
 * @param messageId 消息标识；为空表示该边界没有独立 id
 */
public record UsageBoundary(int messageIndex, Optional<String> messageId) {
    public UsageBoundary {
        if (messageIndex < 0) {
            throw new IllegalArgumentException("messageIndex must be non-negative");
        }
        messageId = Objects.requireNonNull(messageId, "messageId");
        messageId.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("messageId must not be blank when present");
            }
        });
    }
}
