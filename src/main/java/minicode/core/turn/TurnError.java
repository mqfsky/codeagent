package minicode.core.turn;

import java.util.Objects;
import java.util.Optional;

/**
 * 一轮 turn 中发生的错误信息。
 *
 * @param message 面向用户或调用方的错误说明
 * @param source 错误来源
 * @param retryable 当前错误是否适合自动或手动重试
 * @param diagnostics 诊断信息；为空表示没有额外诊断
 * @param causeClass 原始异常类名；为空表示没有捕获到具体异常类型
 */
public record TurnError(String message, TurnErrorSource source, boolean retryable,
                        Optional<String> diagnostics, Optional<String> causeClass) {
    public TurnError {
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        source = Objects.requireNonNull(source, "source");
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        causeClass = Objects.requireNonNull(causeClass, "causeClass");
    }
}
