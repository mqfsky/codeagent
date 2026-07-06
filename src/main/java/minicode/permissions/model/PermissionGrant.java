package minicode.permissions.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 权限系统授予的一次访问许可。
 *
 * @param kind 类型枚举
 * @param resource 请求访问的权限资源
 * @param scope 授权作用域
 * @param persistence 授权是否持久化
 * @param grantedAt 授权时间
 * @param expiresAt 过期时间；为空表示不过期
 */
public record PermissionGrant(PermissionKind kind, PermissionResource resource, PermissionGrantScope scope,
                              PermissionPersistence persistence, Instant grantedAt, Optional<Instant> expiresAt) {
    public PermissionGrant {
        kind = Objects.requireNonNull(kind, "kind");
        resource = Objects.requireNonNull(resource, "resource");
        scope = Objects.requireNonNull(scope, "scope");
        persistence = Objects.requireNonNull(persistence, "persistence");
        grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
