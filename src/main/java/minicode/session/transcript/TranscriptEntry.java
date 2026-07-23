package minicode.session.transcript;

import java.util.Objects;
import java.util.Optional;

/**
 * 会话转写中的一条展示项。
 *
 * @param kind 类型枚举
 * @param body 正文内容
 * @param toolName 工具名称；为空表示该转写项不关联工具
 * @param error 是否表示错误；为空表示该条目没有错误语义
 */
public record TranscriptEntry(Kind kind, String body, Optional<String> toolName, Optional<Boolean> error) {
    public TranscriptEntry {
        kind = Objects.requireNonNull(kind, "kind");
        body = Objects.requireNonNull(body, "body");
        toolName = Objects.requireNonNull(toolName, "toolName");
        error = Objects.requireNonNull(error, "error");
    }

    public enum Kind {
        USER,
        ASSISTANT,
        PROGRESS,
        TOOL,
        COMPACT
    }
}
