package minicode.core.message;

import java.util.Objects;

/**
 * 后台 Agent 任务向父 Agent 投递的一条状态或结果通知。
 *
 * <p>消息以独立类型进入上下文和 session，provider adapter 再把它投影成 user role，
 * 避免恢复会话时丢失任务标识和状态。</p>
 *
 * @param taskId 后台任务标识
 * @param status 任务状态的稳定文本表示
 * @param content 通知正文或结果预览
 */
public record AgentNotificationMessage(String taskId, String status, String content) implements ChatMessage {
    public AgentNotificationMessage {
        taskId = requireText(taskId, "taskId");
        status = requireText(status, "status");
        content = Objects.requireNonNull(content, "content");
    }

    /**
     * 构造发送给模型的稳定通知文本，并转义来自任务数据的 XML 特殊字符。
     */
    public String toModelText() {
        return "<task-notification task_id=\"" + escapeXml(taskId)
                + "\" status=\"" + escapeXml(status) + "\">"
                + escapeXml(content) + "</task-notification>";
    }

    private static String requireText(String value, String name) {
        String actual = Objects.requireNonNull(value, name);
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
