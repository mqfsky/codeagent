package minicode.context.stats;

import minicode.context.accounting.TokenAccountingResult;

import java.util.Objects;

/**
 * 当前上下文窗口占用情况的统计结果。
 *
 * @param accounting token 统计结果
 * @param contextWindow 模型上下文窗口大小
 * @param outputReserve 为模型输出预留的 token 数量
 * @param effectiveInput 扣除输出预留后可用于输入的 token 上限
 * @param utilization 当前输入占有效输入上限的比例
 * @param warningLevel 上下文占用警告等级
 */
public record ContextStats(TokenAccountingResult accounting, long contextWindow, long outputReserve,
                           long effectiveInput, double utilization,
                           ContextWarningLevel warningLevel) {
    public ContextStats {
        accounting = Objects.requireNonNull(accounting, "accounting");
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
        if (effectiveInput <= 0 || effectiveInput != contextWindow - outputReserve) {
            throw new IllegalArgumentException("effectiveInput must equal contextWindow - outputReserve");
        }
        if (utilization < 0.0d) {
            throw new IllegalArgumentException("utilization must be non-negative");
        }
        warningLevel = Objects.requireNonNull(warningLevel, "warningLevel");
    }

    public long maxTokens() {
        return effectiveInput;
    }
}
