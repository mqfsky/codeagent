package minicode.core.message;

import java.time.Instant;
import java.util.Objects;

/**
 * 上下文压缩后生成的摘要消息。
 *
 * <p>它作为 compact boundary 后的可恢复上下文，替代被压缩掉的大段历史消息。</p>
 *
 * @param content 压缩摘要正文
 * @param compressedCount 本次摘要覆盖的历史消息数量
 * @param timestamp 摘要生成时间
 */
public record ContextSummaryMessage(String content, int compressedCount, Instant timestamp) implements ChatMessage {
    public ContextSummaryMessage {
        content = Objects.requireNonNull(content, "content");
        if (compressedCount < 0) {
            throw new IllegalArgumentException("compressedCount must be non-negative");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
