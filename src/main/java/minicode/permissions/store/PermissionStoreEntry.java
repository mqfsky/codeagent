package minicode.permissions.store;

import minicode.permissions.model.PermissionKind;

import java.time.Instant;
import java.util.Objects;

/**
 * 权限存储中的一条决策记录。
 *
 * @param decision 用户或存储中的权限决策
 * @param kind 类型枚举
 * @param resourceKey 权限资源持久化键
 * @param createdAt 创建时间
 */
public record PermissionStoreEntry(PermissionStoreDecision decision, PermissionKind kind,
                                   PermissionResourceKey resourceKey, Instant createdAt) {
    public PermissionStoreEntry {
        decision = Objects.requireNonNull(decision, "decision");
        kind = Objects.requireNonNull(kind, "kind");
        resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
