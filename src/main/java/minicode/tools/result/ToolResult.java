package minicode.tools.result;

import java.util.Objects;
import java.util.Optional;

/**
 * 工具执行返回给 AgentLoop 的结果。
 *
 * @param content 工具返回给模型的文本内容
 * @param error 是否表示工具执行失败
 * @param awaitUser 是否要求等待用户输入
 * @param backgroundTask 后台任务信息；为空表示工具不是后台任务
 */
public record ToolResult(String content, boolean error, boolean awaitUser,
                         Optional<BackgroundTaskResult> backgroundTask) {
    public ToolResult {
        content = Objects.requireNonNull(content, "content");
        backgroundTask = Objects.requireNonNull(backgroundTask, "backgroundTask");
    }

    public static ToolResult ok(String content) {
        return new ToolResult(content, false, false, Optional.empty());
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true, false, Optional.empty());
    }
}
