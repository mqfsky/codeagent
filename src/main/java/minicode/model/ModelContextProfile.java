package minicode.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型上下文能力的解析结果。
 *
 * @param contextWindow 模型上下文窗口大小
 * @param outputReserve 为模型输出预留的 token 数量
 * @param resolvedMaxOutputTokens 解析后的最大输出 token
 * @param source 来源类型
 * @param providerMaxOutputTokens provider 元数据返回的最大输出 token；为空表示未返回
 */
public record ModelContextProfile(long contextWindow, long outputReserve, int resolvedMaxOutputTokens, Source source,
                                  Optional<Integer> providerMaxOutputTokens) {
    public ModelContextProfile {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
        if (resolvedMaxOutputTokens <= 0) {
            throw new IllegalArgumentException("resolvedMaxOutputTokens must be positive");
        }
        source = Objects.requireNonNull(source, "source");
        providerMaxOutputTokens = Objects.requireNonNull(providerMaxOutputTokens, "providerMaxOutputTokens");
    }

    public long effectiveInput() {
        return contextWindow - outputReserve;
    }

    public enum Source {
        PROVIDER_METADATA,
        RUNTIME_CONFIG,
        LOCAL_MODEL_LIMITS,
        UNKNOWN_FALLBACK
    }
}
