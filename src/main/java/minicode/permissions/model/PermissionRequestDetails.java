package minicode.permissions.model;

import java.util.List;
import java.util.Objects;

/**
 * 权限请求的用户可读详情。
 *
 * @param title 标题
 * @param body 正文内容
 * @param facts 补充事实列表
 */
public record PermissionRequestDetails(String title, String body, List<String> facts) {
    public PermissionRequestDetails {
        if (Objects.requireNonNull(title, "title").isBlank()) {
            throw new IllegalArgumentException("details title must not be blank");
        }
        if (Objects.requireNonNull(body, "body").isBlank()) {
            throw new IllegalArgumentException("details body must not be blank");
        }
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
    }

    public static PermissionRequestDetails of(String title, String body) {
        return new PermissionRequestDetails(title, body, List.of());
    }
}
