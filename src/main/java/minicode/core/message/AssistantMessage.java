package minicode.core.message;

import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型返回的最终或普通助手文本消息。
 *
 * <p>该消息会进入后续上下文和会话持久化，用于表示模型已经生成的自然语言回复。</p>
 *
 * @param content 助手回复正文
 * @param providerUsage provider 返回的 token 用量信息；为空表示当前消息没有携带用量
 * @param usageStaleness 用量信息是否仍然可信，以及失效原因
 */
public record AssistantMessage(String content, Optional<ProviderUsage> providerUsage,
                               UsageStaleness usageStaleness) implements ChatMessage {
    public AssistantMessage {
        content = Objects.requireNonNull(content, "content");
        providerUsage = Objects.requireNonNull(providerUsage, "providerUsage");
        usageStaleness = Objects.requireNonNull(usageStaleness, "usageStaleness");
    }

    public AssistantMessage(String content) {
        this(content, Optional.empty(), UsageStaleness.fresh());
    }
}
