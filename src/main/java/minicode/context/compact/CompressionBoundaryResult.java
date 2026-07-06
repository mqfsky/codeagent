package minicode.context.compact;

import minicode.core.message.ContextSummaryMessage;

import java.util.Objects;

/**
 * 上下文压缩边界及摘要结果。
 *
 * @param summaryMessage 压缩后写入上下文的摘要消息
 * @param metadata 压缩元数据
 */
public record CompressionBoundaryResult(ContextSummaryMessage summaryMessage, CompactMetadata metadata) {
    public CompressionBoundaryResult {
        summaryMessage = Objects.requireNonNull(summaryMessage, "summaryMessage");
        metadata = Objects.requireNonNull(metadata, "metadata");
    }
}
