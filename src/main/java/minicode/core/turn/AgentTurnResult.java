package minicode.core.turn;

import minicode.core.message.ChatMessage;
import minicode.session.plan.TurnPersistencePlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Agent 执行一轮 turn 后返回的结果。
 *
 * <p>结果同时包含最新消息上下文、需要持久化的动作，以及本轮停止原因和可选细节。</p>
 *
 * @param messages 本轮结束后的完整消息列表
 * @param persistencePlan 本轮需要追加到 session 日志的持久化计划
 * @param stopReason 本轮停止原因
 * @param stopDetails 与停止原因匹配的附加细节；普通完成、等待用户和达到步数上限时为空
 */
public record AgentTurnResult(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                              AgentTurnStopReason stopReason, Optional<AgentTurnStopDetails> stopDetails) {
    public AgentTurnResult {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        persistencePlan = Objects.requireNonNull(persistencePlan, "persistencePlan");
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
        stopDetails = Objects.requireNonNull(stopDetails, "stopDetails");
        validate(stopReason, stopDetails);
    }

    public static AgentTurnResult create(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                         AgentTurnStopReason stopReason,
                                         Optional<AgentTurnStopDetails> stopDetails) {
        return new AgentTurnResult(messages, persistencePlan, stopReason, stopDetails);
    }

    public static AgentTurnResult finalResult(List<ChatMessage> messages, TurnPersistencePlan persistencePlan) {
        return create(messages, persistencePlan, AgentTurnStopReason.FINAL, Optional.empty());
    }

    public static AgentTurnResult awaitUser(List<ChatMessage> messages, TurnPersistencePlan persistencePlan) {
        return create(messages, persistencePlan, AgentTurnStopReason.AWAIT_USER, Optional.empty());
    }

    public static AgentTurnResult maxSteps(List<ChatMessage> messages, TurnPersistencePlan persistencePlan) {
        return create(messages, persistencePlan, AgentTurnStopReason.MAX_STEPS, Optional.empty());
    }

    public static AgentTurnResult modelError(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                             ModelErrorDetails details) {
        return create(messages, persistencePlan, AgentTurnStopReason.MODEL_ERROR, Optional.of(details));
    }

    public static AgentTurnResult cancelled(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                            CancellationDetails details) {
        return create(messages, persistencePlan, AgentTurnStopReason.CANCELLED, Optional.of(details));
    }

    public static AgentTurnResult emptyFallback(List<ChatMessage> messages, TurnPersistencePlan persistencePlan,
                                                Optional<EmptyFallbackDetails> details) {
        return create(messages, persistencePlan, AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, details.map(AgentTurnStopDetails.class::cast));
    }

    private static void validate(AgentTurnStopReason reason, Optional<AgentTurnStopDetails> details) {
        switch (reason) {
            case FINAL, AWAIT_USER, MAX_STEPS -> {
                if (details.isPresent()) {
                    throw new IllegalArgumentException(reason + " cannot carry stop details");
                }
            }
            case MODEL_ERROR -> requireDetails(details, ModelErrorDetails.class, reason);
            case CANCELLED -> requireDetails(details, CancellationDetails.class, reason);
            case EMPTY_RESPONSE_FALLBACK -> {
                if (details.isPresent() && !(details.get() instanceof EmptyFallbackDetails)) {
                    throw new IllegalArgumentException(reason + " requires EmptyFallbackDetails");
                }
            }
        }
    }

    private static void requireDetails(Optional<AgentTurnStopDetails> details,
                                       Class<? extends AgentTurnStopDetails> type,
                                       AgentTurnStopReason reason) {
        if (details.isEmpty() || !type.isInstance(details.get())) {
            throw new IllegalArgumentException(reason + " requires " + type.getSimpleName());
        }
    }
}
