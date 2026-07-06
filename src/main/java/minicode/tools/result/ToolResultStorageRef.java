package minicode.tools.result;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 持久化工具输出的存储引用。
 *
 * @param id 唯一标识
 * @param path 路径
 * @param bytes 存储内容字节数
 */
public record ToolResultStorageRef(String id, Path path, long bytes) {
    public ToolResultStorageRef {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        path = Objects.requireNonNull(path, "path");
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
    }
}
