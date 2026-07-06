package minicode.session.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * session fork 操作的来源和目标信息。
 *
 * @param sourceSessionId 来源 session id
 * @param sourceEventId 来源事件 id；为空表示没有指定
 * @param newSessionId 新 session id
 * @param cwd 当前 workspace 工作目录
 * @param timestamp 事件或数据生成时间
 */
public record ForkMetadata(String sourceSessionId, Optional<String> sourceEventId, String newSessionId,
                           String cwd, Instant timestamp) {
    public ForkMetadata {
        requireText(sourceSessionId, "sourceSessionId");
        sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        requireText(newSessionId, "newSessionId");
        requireText(cwd, "cwd");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
