package minicode.tools.api;

import minicode.core.turn.CancellationToken;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具执行时可访问的上下文。
 *
 * @param cwd 当前 workspace 工作目录
 * @param sessionId 当前会话 id
 * @param turnId 所属 turn id
 * @param toolUseId 所属工具调用 id
 * @param cancellationToken 工具执行取消令牌
 */
public record ToolContext(Path cwd, String sessionId, Optional<String> turnId, Optional<String> toolUseId,
                          CancellationToken cancellationToken) {
    public ToolContext(Path cwd, String sessionId, Optional<String> turnId, Optional<String> toolUseId) {
        this(cwd, sessionId, turnId, toolUseId, CancellationToken.none());
    }

    public ToolContext {
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        turnId = Objects.requireNonNull(turnId, "turnId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
