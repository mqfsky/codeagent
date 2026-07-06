package minicode.core.turn;

import java.util.Objects;

/**
 * 模型请求失败时附加在 turn 结果上的停止细节。
 *
 * @param error 模型侧错误信息，来源必须是 {@link TurnErrorSource#MODEL}
 */
public record ModelErrorDetails(TurnError error) implements AgentTurnStopDetails {
    public ModelErrorDetails {
        error = Objects.requireNonNull(error, "error");
        if (error.source() != TurnErrorSource.MODEL) {
            throw new IllegalArgumentException("model error details require MODEL source");
        }
    }
}
