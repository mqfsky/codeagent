package minicode.core.message;

import java.util.Objects;

/**
 * 用户输入的普通聊天消息。
 *
 * <p>它表示用户在 TUI 或恢复流程中提交给 Agent 的自然语言请求。</p>
 *
 * @param content 用户输入内容
 */
public record UserMessage(String content) implements ChatMessage {
    public UserMessage {
        content = Objects.requireNonNull(content, "content");
    }
}
