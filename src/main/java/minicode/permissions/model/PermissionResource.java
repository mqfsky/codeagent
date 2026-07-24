package minicode.permissions.model;

import minicode.edit.EditReview;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface PermissionResource permits PermissionResource.PathResource,
        PermissionResource.CommandResource, PermissionResource.EditResource, PermissionResource.McpToolResource,
        PermissionResource.ExternalActionResource {
    /**
     * 路径访问权限资源。
     *
     * @param path 路径
     * @param intent 路径访问意图
     */
    record PathResource(Path path, PathIntent intent) implements PermissionResource {
        public PathResource {
            path = Objects.requireNonNull(path, "path");
            intent = Objects.requireNonNull(intent, "intent");
        }
    }

    /**
     * 命令执行权限资源。
     *
     * @param signature 命令签名
     * @param classification 命令安全分类
     */
    record CommandResource(CommandSignature signature, CommandClassification classification) implements PermissionResource {
        public CommandResource {
            signature = Objects.requireNonNull(signature, "signature");
            classification = Objects.requireNonNull(classification, "classification");
        }
    }

    enum EditOperation {
        CREATE,
        OVERWRITE,
        EDIT,
        PATCH,
        MODIFY
    }

    /**
     * 文件编辑权限资源。
     *
     * @param review 编辑审查信息
     * @param toolUseId 所属工具调用 id
     */
    record EditResource(EditReview review, Optional<String> toolUseId) implements PermissionResource {
        public EditResource {
            review = Objects.requireNonNull(review, "review");
            toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        }

        public Path path() {
            return review.path();
        }

        public EditOperation operation() {
            return review.operation();
        }

        public String summary() {
            return review.summary();
        }

        public String diffPreview() {
            return review.diffPreview();
        }

        public long beforeChars() {
            return review.beforeChars();
        }

        public long afterChars() {
            return review.afterChars();
        }

        public boolean truncated() {
            return review.truncated();
        }

        public boolean originalExists() {
            return review.beforeExists();
        }

        public String reviewFingerprint() {
            return review.reviewFingerprint();
        }

        public Optional<String> diffRef() {
            return review.diffRef();
        }
    }

    /**
     * MCP 工具调用权限资源。
     *
     * @param serverName MCP server 名称
     * @param toolName MCP 工具名称
     * @param wrappedName 包装后的工具名称
     * @param description 描述文本
     */
    record McpToolResource(String serverName, String toolName, String wrappedName,
                           String description) implements PermissionResource {
        public McpToolResource {
            serverName = requireText(serverName, "serverName");
            toolName = requireText(toolName, "toolName");
            wrappedName = requireText(wrappedName, "wrappedName");
            description = Objects.requireNonNull(description, "description");
        }

        private static String requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    /**
     * 会写入外部服务的动作权限资源。
     *
     * @param service 外部服务名称
     * @param action 动作名称
     * @param target 动作目标
     * @param fingerprint 本次动作的显式指纹
     * @param facts 展示给用户的补充事实
     */
    record ExternalActionResource(String service, String action, String target, String fingerprint,
                                  List<String> facts) implements PermissionResource {
        public ExternalActionResource {
            service = requireText(service, "service");
            action = requireText(action, "action");
            target = requireText(target, "target");
            fingerprint = requireText(fingerprint, "fingerprint");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        }

        private static String requireText(String value, String name) {
            if (Objects.requireNonNull(value, name).isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
