package minicode.core.event;

import minicode.tools.result.ToolResultReplacementRecord;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一批工具结果因上下文预算被替换时发布的事件。
 *
 * @param turnId 所属 turn id
 * @param timestamp 事件产生时间
 * @param replacements 被替换的大工具输出记录列表
 */
public record ToolResultsBudgetedEvent(String turnId, Instant timestamp,
                                       List<ToolResultReplacementRecord> replacements) implements AgentEvent {
    public ToolResultsBudgetedEvent {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        replacements = List.copyOf(Objects.requireNonNull(replacements, "replacements"));
    }
}
