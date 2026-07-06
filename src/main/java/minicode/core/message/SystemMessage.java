package minicode.core.message;

import java.util.Objects;

/**
 * 发送给模型的系统提示词消息。
 *
 * <p>系统消息通常放在消息列表最前面，用来描述当前运行环境、工具能力和行为约束。</p>
 *
 * @param content 系统提示词正文
 */
public record SystemMessage(String content) implements ChatMessage {
    public SystemMessage {
        content = Objects.requireNonNull(content, "content");
    }
}
