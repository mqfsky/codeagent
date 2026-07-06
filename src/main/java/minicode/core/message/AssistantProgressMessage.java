package minicode.core.message;

import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型返回的阶段性进展消息。
 *
 * <p>进展消息会展示给用户，但通常不代表 turn 已经完成；AgentLoop 会继续推动下一步。</p>
 *
 * @param content 进展说明文本
 * @param providerUsage provider 返回的 token 用量信息；为空表示当前消息没有携带用量
 * @param usageStaleness 用量信息是否仍然可信，以及失效原因
 */
public record AssistantProgressMessage(String content, Optional<ProviderUsage> providerUsage,
                                       UsageStaleness usageStaleness) implements ChatMessage {
    public AssistantProgressMessage {
        content = Objects.requireNonNull(content, "content");
        providerUsage = Objects.requireNonNull(providerUsage, "providerUsage");
        usageStaleness = Objects.requireNonNull(usageStaleness, "usageStaleness");
    }

    public AssistantProgressMessage(String content) {
        this(content, Optional.empty(), UsageStaleness.fresh());
    }
}
