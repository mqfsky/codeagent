package minicode.skills;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 已加载技能的完整内容。
 *
 * @param name 名称
 * @param description 描述文本
 * @param path 路径
 * @param source 来源类型
 * @param content 技能文件完整正文
 */
public record LoadedSkill(String name, String description, Path path, SkillSource source, String content) {
    public LoadedSkill {
        name = requireText(name, "name");
        description = Objects.requireNonNull(description, "description");
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        source = Objects.requireNonNull(source, "source");
        content = Objects.requireNonNull(content, "content");
    }

    public SkillSummary summary() {
        return new SkillSummary(name, description, path, source);
    }

    private static String requireText(String value, String field) {
        String actualValue = Objects.requireNonNull(value, field);
        if (actualValue.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return actualValue;
    }
}
