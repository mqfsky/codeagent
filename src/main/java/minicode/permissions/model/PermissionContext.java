package minicode.permissions.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 权限判断所处的会话和工具上下文。
 *
 * @param sessionId 当前会话 id
 * @param turnId 所属 turn id
 * @param toolUseId 所属工具调用 id
 */
public record PermissionContext(String sessionId, Optional<String> turnId, Optional<String> toolUseId) {
    public PermissionContext {
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        turnId = Objects.requireNonNull(turnId, "turnId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
    }
}
