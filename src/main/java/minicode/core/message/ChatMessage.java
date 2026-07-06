package minicode.core.message;

/**
 * Agent 会话上下文中的一条聊天消息。
 *
 * <p>这个 sealed interface 统一约束模型请求、工具调用、工具结果和会话持久化中
 * 可以出现的消息类型。通过 {@code permits} 明确列出合法实现后，调用方在 switch
 * 或模式匹配时可以穷尽处理所有消息分支。</p>
 */
public sealed interface ChatMessage permits SystemMessage, UserMessage, AssistantThinkingMessage,
        AssistantMessage, AssistantProgressMessage, AssistantToolCallMessage, ToolResultMessage,
        ContextSummaryMessage {
}
