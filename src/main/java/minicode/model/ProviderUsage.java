package minicode.model;

/**
 * provider 返回的 token 用量。
 *
 * @param inputTokens 输入 token 数量
 * @param outputTokens 输出 token 数量
 * @param totalTokens 总 token 数量
 */
public record ProviderUsage(int inputTokens, int outputTokens, int totalTokens) {
    public ProviderUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("usage token counts must be non-negative");
        }
    }
}
