package minicode.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 解析后的 workspace 路径信息。
 *
 * @param rawPath 用户输入的原始路径
 * @param normalizedPath 规范化后的路径
 * @param realPath 解析符号链接后的真实路径；路径不存在或无法解析时为空
 * @param boundary 路径相对 workspace 边界的位置判断
 */
public record ResolvedWorkspacePath(String rawPath,
                                    Path normalizedPath,
                                    Optional<Path> realPath,
                                    WorkspaceBoundary boundary) {
    public ResolvedWorkspacePath {
        if (Objects.requireNonNull(rawPath, "rawPath").isBlank()) {
            throw new IllegalArgumentException("rawPath must not be blank");
        }
        normalizedPath = Objects.requireNonNull(normalizedPath, "normalizedPath");
        realPath = Objects.requireNonNull(realPath, "realPath");
        boundary = Objects.requireNonNull(boundary, "boundary");
    }
}
