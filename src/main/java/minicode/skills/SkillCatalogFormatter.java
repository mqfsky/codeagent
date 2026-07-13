package minicode.skills;

import java.util.List;
import java.util.Objects;

/**
 * 把当前已发现的 skill 摘要格式化为可直接展示给用户的列表。
 */
public final class SkillCatalogFormatter {
    private SkillCatalogFormatter() {
    }

    /**
     * 渲染 skill 列表；顺序与 {@link SkillRegistry#summaries()} 保持一致。
     *
     * @param skills 当前已发现的 skill 摘要
     * @return 可直接输出到 TUI 的文本
     */
    public static String render(List<SkillSummary> skills) {
        List<SkillSummary> actualSkills = List.copyOf(Objects.requireNonNull(skills, "skills"));
        StringBuilder report = new StringBuilder("Available skills (")
                .append(actualSkills.size())
                .append("):");
        if (actualSkills.isEmpty()) {
            return report.append("\n- none discovered").toString();
        }
        for (SkillSummary skill : actualSkills) {
            report.append("\n- ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description());
        }
        return report.toString();
    }
}
