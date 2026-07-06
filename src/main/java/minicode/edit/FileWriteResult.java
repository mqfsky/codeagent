package minicode.edit;

import minicode.permissions.model.PermissionResource;

import java.util.Objects;
import java.util.Optional;

/**
 * 文件写入操作的执行结果。
 *
 * @param noOp 是否没有实际写入变化
 * @param operation 文件编辑操作类型；为空表示未执行编辑
 * @param message 结果说明消息
 */
public record FileWriteResult(boolean noOp, Optional<PermissionResource.EditOperation> operation, String message) {
    public FileWriteResult {
        operation = Objects.requireNonNull(operation, "operation");
        if (noOp && operation.isPresent()) {
            throw new IllegalArgumentException("no-op write result cannot carry an operation");
        }
        if (!noOp && operation.isEmpty()) {
            throw new IllegalArgumentException("applied write result must carry an operation");
        }
        if (Objects.requireNonNull(message, "message").isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public static FileWriteResult noOp(String message) {
        return new FileWriteResult(true, Optional.empty(), message);
    }

    public static FileWriteResult applied(PermissionResource.EditOperation operation, String message) {
        return new FileWriteResult(false, Optional.of(Objects.requireNonNull(operation, "operation")), message);
    }
}
