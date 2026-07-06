package minicode.core.turn;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型连续返回空响应后 fallback 停止的细节。
 *
 * @param reason fallback 的归类原因；为空表示未提供
 * @param diagnostics 空响应相关诊断；为空表示没有额外诊断
 * @param sawToolResultThisTurn 本轮是否已经收到过工具结果
 * @param toolErrorCount 本轮工具错误数量
 */
public record EmptyFallbackDetails(Optional<String> reason, Optional<String> diagnostics,
                                   boolean sawToolResultThisTurn, int toolErrorCount)
        implements AgentTurnStopDetails {
    public EmptyFallbackDetails {
        reason = Objects.requireNonNull(reason, "reason");
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (toolErrorCount < 0) {
            throw new IllegalArgumentException("toolErrorCount must be non-negative");
        }
    }

    public EmptyFallbackDetails(Optional<String> diagnostics) {
        this(diagnostics, diagnostics, false, 0);
    }
}
