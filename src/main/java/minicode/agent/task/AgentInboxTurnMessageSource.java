package minicode.agent.task;

import minicode.core.message.AgentNotificationMessage;
import minicode.core.message.TurnMessageSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 将持久化的后台任务通知转换为父 {@code AgentLoop} 可消费的消息。 */
public final class AgentInboxTurnMessageSource implements TurnMessageSource {
    private final AgentInbox inbox;
    private final String cwd;

    public AgentInboxTurnMessageSource(AgentInbox inbox, Path cwd) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize().toString();
    }

    @Override
    public List<AgentNotificationMessage> drain(String sessionId, String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        return inbox.drain(cwd, sessionId).stream()
                .map(notification -> new AgentNotificationMessage(
                        notification.taskId(),
                        notification.status().name(),
                        render(notification)))
                .toList();
    }

    private static String render(minicode.agent.model.AgentNotificationMessage notification) {
        StringBuilder content = new StringBuilder()
                .append("Background agent task reached a terminal state.\n")
                .append("agent_id: ").append(notification.agentId()).append('\n')
                .append("agent_type: ").append(externalType(notification)).append('\n')
                .append("description: ").append(notification.description()).append('\n')
                .append("completed_at: ").append(notification.completedAt()).append('\n');
        notification.outputPreview().ifPresent(output -> content
                .append("output_preview:\n")
                .append(output)
                .append('\n'));
        notification.error().ifPresent(error -> content.append("error: ").append(error).append('\n'));
        content.append("Use task_output with task_id=")
                .append(notification.taskId())
                .append(" to read the complete durable result.");
        return content.toString();
    }

    private static String externalType(minicode.agent.model.AgentNotificationMessage notification) {
        return switch (notification.type()) {
            case EXPLORE -> "explore";
            case PLAN -> "plan";
            case GENERAL_PURPOSE -> "general-purpose";
        };
    }
}
