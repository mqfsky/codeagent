package minicode.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * workspace 路径解析结果。
 *
 * @param resolvedPath 解析后的路径信息
 * @param exists 目标路径是否已存在
 * @param parentRealPath 父目录真实路径；无法解析时为空
 */
public record WorkspacePathResult(ResolvedWorkspacePath resolvedPath,
                                  boolean exists,
                                  Optional<Path> parentRealPath) {
    public WorkspacePathResult {
        resolvedPath = Objects.requireNonNull(resolvedPath, "resolvedPath");
        parentRealPath = Objects.requireNonNull(parentRealPath, "parentRealPath");
    }

    public WorkspacePathResult(ResolvedWorkspacePath resolvedPath, boolean exists) {
        this(resolvedPath, exists, Optional.empty());
    }
}
