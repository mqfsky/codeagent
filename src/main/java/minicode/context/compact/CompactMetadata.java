package minicode.context.compact;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次上下文压缩的元数据。
 *
 * @param trigger 压缩触发来源
 * @param tokensBefore 压缩前 token 数量
 * @param tokensAfter 压缩后 token 数量
 * @param messagesCompressed 被压缩的消息数量
 * @param timestamp 事件或数据生成时间
 */
public record CompactMetadata(CompactTrigger trigger, long tokensBefore, long tokensAfter,
                              int messagesCompressed, Instant timestamp) {
    public CompactMetadata {
        trigger = Objects.requireNonNull(trigger, "trigger");
        if (tokensBefore < 0 || tokensAfter < 0 || messagesCompressed < 0) {
            throw new IllegalArgumentException("compact counts must be non-negative");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
