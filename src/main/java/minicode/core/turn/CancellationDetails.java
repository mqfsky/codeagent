package minicode.core.turn;

import java.util.Objects;

/**
 * turn 被取消时附加在结果上的停止细节。
 *
 * @param cancellation 取消来源、阶段和原因
 */
public record CancellationDetails(TurnCancellation cancellation) implements AgentTurnStopDetails {
    public CancellationDetails {
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }
}
