package minicode.permissions.model;

import java.util.Objects;
import java.util.Optional;

/**
 * 命令权限请求的展示数据。
 *
 * @param requestId 权限请求 id
 * @param signature 命令签名
 * @param classification 命令安全分类
 * @param cwd 当前 workspace 工作目录
 * @param toolUseId 所属工具调用 id
 */
public record CommandPermissionRequest(String requestId, CommandSignature signature,
                                       CommandClassification classification, String cwd,
                                       Optional<String> toolUseId) {
    public CommandPermissionRequest {
        if (Objects.requireNonNull(requestId, "requestId").isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        signature = Objects.requireNonNull(signature, "signature");
        classification = Objects.requireNonNull(classification, "classification");
        if (Objects.requireNonNull(cwd, "cwd").isBlank()) {
            throw new IllegalArgumentException("cwd must not be blank");
        }
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
    }
}
