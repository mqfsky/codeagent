package minicode.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.model.ProviderUsage;
import minicode.model.UsageStaleness;

import java.util.Objects;
import java.util.Optional;

/**
 * 模型发起的一次工具调用消息。
 *
 * <p>该消息对应 provider 响应中的 tool use 块，后续的工具结果会通过同一个
 * {@code toolUseId} 与它配对。</p>
 *
 * @param toolUseId provider 分配的工具调用 id
 * @param toolName 要调用的工具名称
 * @param input 传给工具的 JSON 输入
 * @param providerUsage provider 返回的 token 用量信息；为空表示当前消息没有携带用量
 * @param usageStaleness 用量信息是否仍然可信，以及失效原因
 */
public record AssistantToolCallMessage(String toolUseId, String toolName, JsonNode input,
                                       Optional<ProviderUsage> providerUsage,
                                       UsageStaleness usageStaleness) implements ChatMessage {
    public AssistantToolCallMessage {
        requireText(toolUseId, "toolUseId");
        requireText(toolName, "toolName");
        input = Objects.requireNonNull(input, "input");
        providerUsage = Objects.requireNonNull(providerUsage, "providerUsage");
        usageStaleness = Objects.requireNonNull(usageStaleness, "usageStaleness");
    }

    public AssistantToolCallMessage(String toolUseId, String toolName, JsonNode input) {
        this(toolUseId, toolName, input, Optional.empty(), UsageStaleness.fresh());
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
