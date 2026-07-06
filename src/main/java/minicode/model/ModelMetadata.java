package minicode.model;

import java.util.Objects;
import java.util.Optional;

/**
 * provider 返回的模型元数据。
 *
 * @param id 唯一标识
 * @param maxInputTokens 最大输入 token；为空表示 provider 未返回
 * @param maxOutputTokens 最大输出 token；为空表示 provider 未返回
 */
public record ModelMetadata(String id, Optional<Long> maxInputTokens, Optional<Integer> maxOutputTokens) {
    public ModelMetadata {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        maxInputTokens = Objects.requireNonNull(maxInputTokens, "maxInputTokens");
        maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
    }
}
