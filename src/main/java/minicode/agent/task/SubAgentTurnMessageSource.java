package minicode.agent.task;

import minicode.core.message.ChatMessage;
import minicode.core.message.TurnMessageSource;
import minicode.core.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将一次性内存通知转换为父 Agent 可见的临时用户角色提醒。 */
public final class SubAgentTurnMessageSource implements TurnMessageSource {
    private final SubAgentTaskManager taskManager;

    public SubAgentTurnMessageSource(SubAgentTaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public List<ChatMessage> drain(String sessionId, String turnId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(turnId, "turnId");
        List<ChatMessage> messages = new ArrayList<>();
        for (SubAgentTaskManager.TaskNotification notification : taskManager.drainNotifications()) {
            messages.add(new UserMessage(render(notification)));
        }
        return List.copyOf(messages);
    }

    private static String render(SubAgentTaskManager.TaskNotification notification) {
        return """
                <system-reminder>
                <task-notification>
                <task_id>%s</task_id>
                <status>%s</status>
                <result>%s</result>
                </task-notification>
                </system-reminder>
                """.formatted(
                notification.taskId(),
                notification.status().name(),
                Objects.requireNonNullElse(notification.output(), "")).strip();
    }
}
