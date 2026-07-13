package minicode.context.boundary;

import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ContextBoundaryGuard {
    private ContextBoundaryGuard() {
    }

    /**
     * 检查当前消息列表是否停在可以安全压缩的边界。
     * <p>
     * 每个工具调用都必须在后面有且仅有一个使用相同 {@code toolUseId} 的工具结果；
     * 重复调用、孤立结果、重复结果或尚未返回结果的调用都会被视为不安全。
     *
     * @param messages 待检查的完整消息列表
     * @return 所有工具调用均正确闭合时返回 {@code true}
     */
    public static boolean isCompactSafeBoundary(List<ChatMessage> messages) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (actualMessages.isEmpty()) {
            return true;
        }

        // 记录已发起但还没有收到结果的工具调用 ID。
        Set<String> openToolCalls = new HashSet<>();
        // 记录已经收到结果的工具调用 ID，用于防止 ID 被重复使用。
        Set<String> completedToolCalls = new HashSet<>();

        for (ChatMessage message : actualMessages) {
            if (message instanceof AssistantToolCallMessage toolCall) {
                // 同一 ID 不能同时存在多个未完成调用，也不能在完成后再次使用。
                if (openToolCalls.contains(toolCall.toolUseId()) || completedToolCalls.contains(toolCall.toolUseId())) { // 即使是同样的工具，工具 id 也是不一样的，call001，call002
                    return false;
                }
                // 新调用暂时处于“未闭合”状态，等待对应的 ToolResultMessage。
                openToolCalls.add(toolCall.toolUseId());
            } else if (message instanceof ToolResultMessage toolResult) {
                // 只有前面存在同 ID 的未闭合调用时，这条结果才合法。
                if (!openToolCalls.remove(toolResult.toolUseId())) {
                    return false;
                }
                // 一个工具调用只能完成一次，拒绝重复结果。
                if (!completedToolCalls.add(toolResult.toolUseId())) {
                    return false;
                }
            }
        }

        // 遍历结束后仍有未闭合调用，说明此时仍处于工具调用过程中，不能压缩。
        return openToolCalls.isEmpty();
    }
}
