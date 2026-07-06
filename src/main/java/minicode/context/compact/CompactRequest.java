package minicode.context.compact;

import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

/**
 * 发起上下文压缩时的请求参数。
 *
 * @param messages 压缩或执行后的消息列表
 * @param modelAdapter 用于生成压缩摘要的模型适配器
 * @param trigger 压缩触发来源
 */
public record CompactRequest(List<ChatMessage> messages, ModelAdapter modelAdapter, CompactTrigger trigger) {
    public CompactRequest {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        trigger = Objects.requireNonNull(trigger, "trigger");
    }
}
