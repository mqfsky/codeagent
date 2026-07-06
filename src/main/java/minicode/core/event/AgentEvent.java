package minicode.core.event;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.context.compact.AutoCompactEventType;
import minicode.context.compact.CompressionResult;
import minicode.context.stats.ContextStats;
import minicode.core.turn.TurnCancellation;
import minicode.core.message.ChatMessage;
import minicode.tools.result.ToolResultReplacementRecord;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public sealed interface AgentEvent permits AgentEvent.AssistantMessageEvent, AgentEvent.ToolStartedEvent,
        AgentEvent.ToolFinishedEvent, AgentEvent.ContextStatsEvent, AgentEvent.AutoCompactEvent,
        AgentEvent.AwaitUserEvent, AgentEvent.TurnCancelledEvent, ToolResultsBudgetedEvent {
    String turnId();

    Instant timestamp();

    /**
     * Agent 追加助手侧消息时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param message 新追加的消息
     */
    record AssistantMessageEvent(String turnId, Instant timestamp, ChatMessage message) implements AgentEvent {
        public AssistantMessageEvent {
            requireEvent(turnId, timestamp);
            message = Objects.requireNonNull(message, "message");
        }
    }

    /**
     * 工具开始执行时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param toolUseId 工具调用 id
     * @param toolName 工具名称
     * @param input 工具输入 JSON
     */
    record ToolStartedEvent(String turnId, Instant timestamp, String toolUseId, String toolName,
                            JsonNode input) implements AgentEvent {
        public ToolStartedEvent {
            requireEvent(turnId, timestamp);
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            input = Objects.requireNonNull(input, "input");
        }
    }

    /**
     * 工具执行完成时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param toolUseId 工具调用 id
     * @param toolName 工具名称
     * @param error 工具是否执行失败
     * @param awaitUser 工具结果是否要求等待用户补充输入
     * @param replacement 大工具输出被替换时的存储记录；为空表示未替换
     */
    record ToolFinishedEvent(String turnId, Instant timestamp, String toolUseId, String toolName,
                             boolean error, boolean awaitUser,
                             Optional<ToolResultReplacementRecord> replacement) implements AgentEvent {
        public ToolFinishedEvent {
            requireEvent(turnId, timestamp);
            requireText(toolUseId, "toolUseId");
            requireText(toolName, "toolName");
            replacement = Objects.requireNonNull(replacement, "replacement");
        }
    }

    /**
     * 上下文窗口统计刷新时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param stats 当前上下文 token 统计
     */
    record ContextStatsEvent(String turnId, Instant timestamp, ContextStats stats) implements AgentEvent {
        public ContextStatsEvent {
            requireEvent(turnId, timestamp);
            stats = Objects.requireNonNull(stats, "stats");
        }
    }

    /**
     * 自动上下文压缩流程状态变化时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param type 自动压缩事件类型
     * @param result 压缩完成时的结果；未完成时为空
     * @param reason 跳过或失败原因；没有原因时为空
     */
    record AutoCompactEvent(String turnId, Instant timestamp, AutoCompactEventType type,
                            Optional<CompressionResult> result, Optional<String> reason) implements AgentEvent {
        public AutoCompactEvent {
            requireEvent(turnId, timestamp);
            type = Objects.requireNonNull(type, "type");
            result = Objects.requireNonNull(result, "result");
            reason = Objects.requireNonNull(reason, "reason");
            if (type == AutoCompactEventType.COMPLETED && result.isEmpty()) {
                throw new IllegalArgumentException("COMPLETED auto compact event requires result");
            }
            if (type != AutoCompactEventType.COMPLETED && result.isPresent()) {
                throw new IllegalArgumentException(type + " auto compact event must not carry result");
            }
        }
    }

    /**
     * 工具请求等待用户输入时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param toolUseId 触发等待的工具调用 id
     * @param question 展示给用户的问题
     */
    record AwaitUserEvent(String turnId, Instant timestamp, String toolUseId, String question) implements AgentEvent {
        public AwaitUserEvent {
            requireEvent(turnId, timestamp);
            requireText(toolUseId, "toolUseId");
            requireText(question, "question");
        }
    }

    /**
     * turn 被取消时发布的事件。
     *
     * @param turnId 所属 turn id
     * @param timestamp 事件产生时间
     * @param cancellation 取消来源、阶段和原因
     */
    record TurnCancelledEvent(String turnId, Instant timestamp, TurnCancellation cancellation) implements AgentEvent {
        public TurnCancelledEvent {
            requireEvent(turnId, timestamp);
            cancellation = Objects.requireNonNull(cancellation, "cancellation");
        }
    }

    private static void requireEvent(String turnId, Instant timestamp) {
        requireText(turnId, "turnId");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
