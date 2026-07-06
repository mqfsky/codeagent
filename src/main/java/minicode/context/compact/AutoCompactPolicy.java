package minicode.context.compact;

/**
 * 自动上下文压缩的触发策略。
 *
 * @param utilizationThreshold 触发自动压缩的上下文使用率阈值
 * @param maxFailures 自动压缩连续失败后允许的最大次数
 * @param minEffectiveInput 尝试自动压缩前要求的最小有效输入 token
 * @param failureCooldownPreflights 自动压缩失败后的预检冷却轮数
 */
public record AutoCompactPolicy(double utilizationThreshold, int maxFailures, long minEffectiveInput,
                                int failureCooldownPreflights) {
    public AutoCompactPolicy {
        if (utilizationThreshold <= 0.0d || utilizationThreshold > 1.0d) {
            throw new IllegalArgumentException("utilizationThreshold must be in (0, 1]");
        }
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
        if (minEffectiveInput < 0) {
            throw new IllegalArgumentException("minEffectiveInput must be non-negative");
        }
        if (failureCooldownPreflights < 0) {
            throw new IllegalArgumentException("failureCooldownPreflights must be non-negative");
        }
    }

    public static AutoCompactPolicy defaults() {
        return new AutoCompactPolicy(0.85d, 3, 20_000, 2);
    }
}
