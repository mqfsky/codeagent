package minicode.core.turn;

import java.util.Objects;

/**
 * 一次 turn 被取消时的原因描述。
 *
 * @param source 取消信号来源
 * @param phase 取消发生时 AgentLoop 所处阶段
 * @param reason 取消原因说明
 */
public record TurnCancellation(CancellationSource source, CancellationPhase phase, String reason) {
    public TurnCancellation {
        source = Objects.requireNonNull(source, "source");
        phase = Objects.requireNonNull(phase, "phase");
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
