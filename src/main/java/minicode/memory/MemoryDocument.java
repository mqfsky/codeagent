package minicode.memory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * MiniCode 分层项目记忆中加载的一份指令文档。
 *
 * @param path      源文件路径
 * @param scope     提供该文档的记忆层级
 * @param depth     相对于项目根目录的目录深度；全局和项目根目录文件使用 {@code 0}
 * @param content   经过规范化且符合 Prompt 预算限制的文档内容
 * @param truncated 文档是否因预算限制而被截断
 */
public record MemoryDocument(Path path, Scope scope, int depth, String content, boolean truncated) {
    public enum Scope {
        GLOBAL,
        PROJECT_ROOT,
        SUBDIRECTORY,
        RULE
    }

    public MemoryDocument {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        scope = Objects.requireNonNull(scope, "scope");
        content = Objects.requireNonNull(content, "content");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be non-negative");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
