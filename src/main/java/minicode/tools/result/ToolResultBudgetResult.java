package minicode.tools.result;

import minicode.core.message.ToolResultMessage;

import java.util.List;
import java.util.Objects;

/**
 * 工具结果预算裁剪后的结果。
 *
 * @param results 预算处理后的工具结果列表
 * @param replacements 发生替换的工具结果记录列表
 */
public record ToolResultBudgetResult(List<ToolResultMessage> results, List<ToolResultReplacementRecord> replacements) {
    public ToolResultBudgetResult {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
        replacements = List.copyOf(Objects.requireNonNull(replacements, "replacements"));
    }
}
