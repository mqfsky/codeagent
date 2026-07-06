package minicode.context.accounting;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次 token 统计的结果。
 *
 * @param inputTokens 输入 token 数量
 * @param outputTokens 输出 token 数量
 * @param totalTokens 总 token 数量
 * @param providerUsageTokens 直接来自 provider usage 的 token 数量
 * @param estimatedTokens 本地估算补齐的 token 数量
 * @param isExact 统计是否完全来自 provider 精确用量
 * @param source token 统计来源
 * @param usageBoundary provider 用量归属的消息边界；估算-only 时为空
 * @param stale 用量信息是否已经失效
 * @param reason 原因说明；为空表示没有额外原因
 */
public record TokenAccountingResult(long inputTokens, long outputTokens, long totalTokens,
                                    long providerUsageTokens, long estimatedTokens, boolean isExact,
                                    TokenAccountingSource source, Optional<UsageBoundary> usageBoundary,
                                    boolean stale, Optional<String> reason) {
    public TokenAccountingResult {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0
                || providerUsageTokens < 0 || estimatedTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
        if (providerUsageTokens + estimatedTokens != totalTokens) {
            throw new IllegalArgumentException("totalTokens must equal providerUsageTokens + estimatedTokens");
        }
        if (isExact && estimatedTokens != 0) {
            throw new IllegalArgumentException("exact accounting cannot include estimated tokens");
        }
        source = Objects.requireNonNull(source, "source");
        usageBoundary = Objects.requireNonNull(usageBoundary, "usageBoundary");
        reason = Objects.requireNonNull(reason, "reason");
        if (stale && reason.filter(value -> !value.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("stale accounting requires a reason");
        }
        if (source == TokenAccountingSource.ESTIMATE_ONLY && usageBoundary.isPresent()) {
            throw new IllegalArgumentException("estimate-only accounting cannot have a usage boundary");
        }
        if (source != TokenAccountingSource.ESTIMATE_ONLY && usageBoundary.isEmpty()) {
            throw new IllegalArgumentException("provider accounting requires a usage boundary");
        }
    }

    public static TokenAccountingResult providerUsage(long inputTokens, long outputTokens, long totalTokens,
                                                       UsageBoundary usageBoundary) {
        return new TokenAccountingResult(
                inputTokens,
                outputTokens,
                totalTokens,
                totalTokens,
                0,
                true,
                TokenAccountingSource.PROVIDER_USAGE,
                Optional.of(usageBoundary),
                false,
                Optional.empty()
        );
    }

    public static TokenAccountingResult providerUsageWithEstimate(long inputTokens, long outputTokens,
                                                                   long totalTokens,
                                                                   long providerUsageTokens,
                                                                   long estimatedTokens,
                                                                   UsageBoundary usageBoundary) {
        return new TokenAccountingResult(
                inputTokens,
                outputTokens,
                totalTokens,
                providerUsageTokens,
                estimatedTokens,
                false,
                TokenAccountingSource.PROVIDER_USAGE_WITH_ESTIMATE,
                Optional.of(usageBoundary),
                false,
                Optional.empty()
        );
    }

    public static TokenAccountingResult estimateOnly(long estimatedTokens, Optional<String> staleReason) {
        return new TokenAccountingResult(
                estimatedTokens,
                0,
                estimatedTokens,
                0,
                estimatedTokens,
                false,
                TokenAccountingSource.ESTIMATE_ONLY,
                Optional.empty(),
                staleReason.isPresent(),
                staleReason.or(() -> Optional.of("no provider usage available"))
        );
    }
}
