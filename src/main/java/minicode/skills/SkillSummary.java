package minicode.skills;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 技能在系统提示词中的摘要。
 *
 * @param name 名称
 * @param description 描述文本
 * @param path 路径
 * @param source 来源类型
 */
public record SkillSummary(String name, String description, Path path, SkillSource source) {
    public SkillSummary {
        name = requireText(name, "name");
        description = Objects.requireNonNull(description, "description");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        source = Objects.requireNonNull(source, "source");
    }

    private static String requireText(String value, String field) {
        String actualValue = Objects.requireNonNull(value, field);
        if (actualValue.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return actualValue;
    }
}
