package minicode.context.accounting;

import minicode.core.message.*;
import minicode.model.ProviderUsage;

import java.util.List;
import java.util.Optional;

public final class TokenAccountingService {
    public TokenAccountingResult account(List<ChatMessage> messages) {
        // 从后往前找最近一条“带 provider usage 且 usage 没过期”的消息，把它当作 token 统计的可信边界。
        int boundary = latestFreshProviderUsageBoundary(messages);
        // 存在可信边界
        if (boundary >= 0) {
            // 获取到边界的 token 使用率
            ProviderUsage usage = providerUsage(messages.get(boundary)).orElseThrow();
            // 估算边界到最新消息的 token 使用率
            long estimatedTail = estimate(messages.subList(boundary + 1, messages.size()));

            UsageBoundary usageBoundary = new UsageBoundary(boundary, messageBoundaryId(messages.get(boundary)));
            // 有新消息的估算，就在边界的基础上加上
            if (estimatedTail > 0) {
                return TokenAccountingResult.providerUsageWithEstimate(
                        usage.inputTokens() + estimatedTail,
                        usage.outputTokens(),
                        usage.totalTokens() + estimatedTail,
                        usage.totalTokens(),
                        estimatedTail,
                        usageBoundary
                );
            }
            // 没有就直接返回边界提供的使用率
            return TokenAccountingResult.providerUsage(
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.totalTokens(),
                    usageBoundary
            );
        }
        // 没有边界，完整消息估计
        long estimate = estimate(messages);
        return TokenAccountingResult.estimateOnly(estimate, staleUsageReason(messages));
    }

    /**
     * 从后往前找最近一条“带 provider usage 且 usage 没过期”的消息，把它当作 token 统计的可信边界。
     *
     * @param messages 消息列表
     * @return idx
     */
    private int latestFreshProviderUsageBoundary(List<ChatMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            // 根据 message 类型获取 provider 的 token 用量
            Optional<ProviderUsage> usage = providerUsage(message);
            // 存在 usage 并且 未失效
            if (usage.isPresent() && !usageStaleness(message).stale()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 从消息中提取模型供应商返回的 token 用量。
     *
     * <p>只有模型生成的最终回答、过程消息和工具调用消息可能携带 {@link ProviderUsage}；
     * 用户消息、系统消息和工具结果等其他消息不是模型响应，因此返回空值。</p>
     *
     * @param message 需要检查的聊天消息
     * @return 消息携带的供应商 token 用量；消息类型不支持或供应商未返回 usage 时为空
     */
    private Optional<ProviderUsage> providerUsage(ChatMessage message) {
        return switch (message) {
            // 模型最终文本回答中记录的输入、输出和总 token 数。
            case AssistantMessage assistant -> assistant.providerUsage();
            // 模型过程性回答中携带的 token 用量。
            case AssistantProgressMessage progress -> progress.providerUsage();
            // 模型生成工具调用时，该次模型响应携带的 token 用量。
            case AssistantToolCallMessage toolCall -> toolCall.providerUsage();
            // 用户、系统、工具结果等消息没有供应商 usage。
            default -> Optional.empty();
        };
    }

    private minicode.model.UsageStaleness usageStaleness(ChatMessage message) {
        return switch (message) {
            case AssistantMessage assistant -> assistant.usageStaleness();
            case AssistantProgressMessage progress -> progress.usageStaleness();
            case AssistantToolCallMessage toolCall -> toolCall.usageStaleness();
            default -> minicode.model.UsageStaleness.fresh();
        };
    }

    private Optional<String> staleUsageReason(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            Optional<ProviderUsage> usage = providerUsage(message);
            minicode.model.UsageStaleness staleness = usageStaleness(message);
            if (usage.isPresent() && staleness.stale()) {
                return staleness.reason().or(() -> Optional.of("provider usage was marked stale"));
            }
        }
        return Optional.empty();
    }

    private Optional<String> messageBoundaryId(ChatMessage message) {
        if (message instanceof AssistantToolCallMessage toolCall) {
            return Optional.of(toolCall.toolUseId());
        }
        return Optional.empty();
    }

    private long estimate(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        long chars = 0;
        for (ChatMessage message : messages) {
            chars += switch (message) {
                case SystemMessage system -> system.content().length();
                case UserMessage user -> user.content().length();
                case AgentNotificationMessage notification -> notification.toModelText().length();
                case AssistantMessage assistant -> assistant.content().length();
                case AssistantProgressMessage progress -> progress.content().length();
                case ToolResultMessage toolResult -> toolResult.content().length();
                case ContextSummaryMessage summary -> summary.content().length();
                // toolname + toolinput
                case AssistantToolCallMessage toolCall -> toolCall.toolName().length() + toolCall.input().toString().length();
                case AssistantThinkingMessage thinking -> thinking.blocks().toString().length();
                default -> 0;
            };
        }
        // 按照 4 个字符约等于 1 个 token 估算
        return Math.max(1, (chars + 3) / 4);
    }
}
