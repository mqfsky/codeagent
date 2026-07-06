package minicode.context.stats;

/**
 * 模型上下文窗口与输出预留空间配置。
 *
 * @param contextWindow 模型上下文窗口大小
 * @param outputReserve 为模型输出预留的 token 数量
 */
public record ModelContextWindow(long contextWindow, long outputReserve) {
    public ModelContextWindow {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (outputReserve < 0 || outputReserve >= contextWindow) {
            throw new IllegalArgumentException("outputReserve must be non-negative and smaller than contextWindow");
        }
    }

    public long effectiveInput() {
        return contextWindow - outputReserve;
    }
}
