package minicode.tools.builtin;

import minicode.permissions.model.CommandClassification;

import java.util.Objects;

/**
 * 命令安全分类的结果。
 *
 * @param classification 命令安全分类
 * @param shellSnippet 命令是否被识别为 shell 片段
 * @param reason 原因说明；为空表示没有额外原因
 */
public record CommandClassificationResult(CommandClassification classification, boolean shellSnippet, String reason) {
    public CommandClassificationResult {
        classification = Objects.requireNonNull(classification, "classification");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
