package minicode.agent.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 后台任务进入终态后注入父 Turn 的通知。 */
public record AgentNotificationMessage(String taskId,
                                       String agentId,
                                       AgentType type,
                                       String description,
                                       AgentTaskStatus status,
                                       Optional<String> outputPreview,
                                       Optional<String> error,
                                       Instant completedAt) {
    public AgentNotificationMessage {
        taskId = requireText(taskId, "taskId");
        agentId = requireText(agentId, "agentId");
        type = Objects.requireNonNull(type, "type");
        description = requireText(description, "description");
        status = Objects.requireNonNull(status, "status");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("Notification requires a terminal task");
        }
        outputPreview = Objects.requireNonNull(outputPreview, "outputPreview");
        error = Objects.requireNonNull(error, "error");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
