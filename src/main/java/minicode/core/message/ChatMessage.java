package minicode.core.message;

/**
 * Agent 会话上下文中的一条聊天消息。
 *
 * <p>这个 sealed interface 统一约束模型请求、工具调用、工具结果和会话持久化中
 * 可以出现的消息类型。通过 {@code permits} 明确列出合法实现后，调用方在 switch
 * 或模式匹配时可以穷尽处理所有消息分支。</p>
 *
 * <p>当前支持的消息类型：</p>
 * <ul>
 *     <li>{@link SystemMessage}：系统提示词，描述当前工作目录、工具、技能和行为约束。</li>
 *     <li>{@link UserMessage}：用户输入，表示用户在 TUI 中提交的一轮请求。</li>
 *     <li>{@link AssistantMessage}：模型返回的普通助手回复，通常表示最终回答或普通文本输出。</li>
 *     <li>{@link AssistantProgressMessage}：模型返回的阶段性进展，表示任务还在继续推进。</li>
 *     <li>{@link AssistantThinkingMessage}：provider 返回的 thinking block，用来单独保存模型思考块。</li>
 *     <li>{@link AssistantToolCallMessage}：模型发起的工具调用，记录工具名、调用 id 和输入参数。</li>
 *     <li>{@link ToolResultMessage}：工具执行后的结果，通过 tool use id 回填给模型继续推理。</li>
 *     <li>{@link AgentNotificationMessage}：后台 Agent 任务完成或状态变化后注入当前 turn 的通知。</li>
 *     <li>{@link ContextSummaryMessage}：上下文压缩后的摘要，用来替代被压缩掉的历史消息。</li>
 * </ul>
 */
public sealed interface ChatMessage permits SystemMessage, UserMessage, AssistantThinkingMessage,
        AssistantMessage, AssistantProgressMessage, AssistantToolCallMessage, ToolResultMessage,
        AgentNotificationMessage, ContextSummaryMessage {
}
