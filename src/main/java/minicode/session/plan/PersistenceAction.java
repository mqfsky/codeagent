package minicode.session.plan;

import minicode.core.message.ChatMessage;
import minicode.context.compact.CompactMetadata;
import minicode.session.model.MetaSessionEventDraft;

import java.util.List;
import java.util.Objects;

public sealed interface PersistenceAction permits PersistenceAction.AppendMessagesAction,
        PersistenceAction.AppendCompactBoundaryAction, PersistenceAction.AppendSessionEventAction {
    /**
     * 追加消息到 session 日志的持久化动作。
     *
     * @param messages 压缩或执行后的消息列表
     */
    record AppendMessagesAction(List<ChatMessage> messages) implements PersistenceAction {
        public AppendMessagesAction {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("append messages action requires at least one message");
            }
        }
    }

    /**
     * 追加压缩边界到 session 日志的持久化动作。
     *
     * @param summaryMessage 压缩后写入上下文的摘要消息
     * @param metadata 压缩元数据
     */
    record AppendCompactBoundaryAction(ChatMessage summaryMessage, CompactMetadata metadata) implements PersistenceAction {
        public AppendCompactBoundaryAction {
            summaryMessage = Objects.requireNonNull(summaryMessage, "summaryMessage");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    /**
     * 追加会话元事件的持久化动作，比如 rename，fork
     *
     * @param draft 会话元事件草稿
     */
    record AppendSessionEventAction(MetaSessionEventDraft draft) implements PersistenceAction {
        public AppendSessionEventAction {
            draft = Objects.requireNonNull(draft, "draft");
        }
    }
}
