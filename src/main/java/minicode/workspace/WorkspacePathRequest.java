package minicode.workspace;

import minicode.permissions.model.PathIntent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 请求解析 workspace 路径的参数。
 *
 * @param cwd 当前 workspace 工作目录
 * @param rawPath 用户输入的原始路径
 * @param intent 路径访问意图
 * @param mustExist 路径是否必须已经存在
 * @param allowDirectory 是否允许目录路径
 * @param policy 路径存在性和类型约束策略
 */
public record WorkspacePathRequest(Path cwd,
                                   String rawPath,
                                   PathIntent intent,
                                   boolean mustExist,
                                   boolean allowDirectory,
                                   WorkspacePathPolicy policy) {
    public WorkspacePathRequest {
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(rawPath, "rawPath").isBlank()) {
            throw new IllegalArgumentException("rawPath must not be blank");
        }
        intent = Objects.requireNonNull(intent, "intent");
        policy = Objects.requireNonNull(policy, "policy");
    }

    public WorkspacePathRequest(Path cwd, String rawPath, PathIntent intent,
                                boolean mustExist, boolean allowDirectory) {
        this(cwd, rawPath, intent, mustExist, allowDirectory, policyFrom(mustExist, allowDirectory));
    }

    public WorkspacePathRequest(Path cwd, String rawPath, PathIntent intent, WorkspacePathPolicy policy) {
        this(cwd, rawPath, intent, policy.mustExist(), policy.allowDirectory(), policy);
    }

    private static WorkspacePathPolicy policyFrom(boolean mustExist, boolean allowDirectory) {
        if (!mustExist) {
            return WorkspacePathPolicy.TARGET_OR_EXISTING_PARENT;
        }
        return allowDirectory ? WorkspacePathPolicy.EXISTING_DIRECTORY : WorkspacePathPolicy.EXISTING_FILE;
    }
}
