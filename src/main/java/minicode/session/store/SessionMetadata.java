package minicode.session.store;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 用于 session 列表展示的元数据。
 *
 * @param sessionId 当前会话 id
 * @param cwd 当前 workspace 工作目录
 * @param title 标题
 * @param eventCount session 中事件数量
 * @param updatedAt session 最近更新时间
 * @param path 路径
 */
public record SessionMetadata(String sessionId, String cwd, Optional<String> title, int eventCount,
                              Instant updatedAt, Path path) {
    public SessionMetadata {
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (Objects.requireNonNull(cwd, "cwd").isBlank()) {
            throw new IllegalArgumentException("cwd must not be blank");
        }
        title = Objects.requireNonNull(title, "title");
        if (eventCount < 0) {
            throw new IllegalArgumentException("eventCount must be non-negative");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        path = Objects.requireNonNull(path, "path");
    }
}
