package minicode.model;

import java.util.Objects;
import java.util.Optional;

/**
 * provider 用量信息的新鲜度标记。
 *
 * @param stale 用量信息是否已经失效
 * @param reason 原因说明；为空表示没有额外原因
 */
public record UsageStaleness(boolean stale, Optional<String> reason) {
    public UsageStaleness {
        reason = Objects.requireNonNull(reason, "reason");
        if (stale && reason.filter(value -> !value.isBlank()).isEmpty()) {
            throw new IllegalArgumentException("stale usage requires a reason");
        }
        if (!stale && reason.isPresent()) {
            throw new IllegalArgumentException("fresh usage cannot carry a stale reason");
        }
    }

    public static UsageStaleness fresh() {
        return new UsageStaleness(false, Optional.empty());
    }

    public static UsageStaleness stale(String reason) {
        return new UsageStaleness(true, Optional.of(reason));
    }
}
