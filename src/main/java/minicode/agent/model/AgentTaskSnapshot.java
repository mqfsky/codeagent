package minicode.agent.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 单个 Agent 委派任务的持久化不可变视图。 */
public record AgentTaskSnapshot(String taskId,
                                String agentId,
                                AgentType type,
                                String description,
                                String parentSessionId,
                                String parentTurnId,
                                String cwd,
                                AgentTaskStatus status,
                                Instant submittedAt,
                                Optional<Instant> startedAt,
                                Optional<Instant> completedAt,
                                Optional<String> output,
                                Optional<String> error,
                                boolean notificationDelivered) {
    public AgentTaskSnapshot {
        taskId = requireText(taskId, "taskId");
        agentId = requireText(agentId, "agentId");
        type = Objects.requireNonNull(type, "type");
        description = requireText(description, "description");
        parentSessionId = requireText(parentSessionId, "parentSessionId");
        parentTurnId = requireText(parentTurnId, "parentTurnId");
        cwd = requireText(cwd, "cwd");
        status = Objects.requireNonNull(status, "status");
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        output = Objects.requireNonNull(output, "output");
        error = Objects.requireNonNull(error, "error").map(value -> requireText(value, "error"));
        validateLifecycle(status, startedAt, completedAt, notificationDelivered);
    }

    public static AgentTaskSnapshot queued(AgentTaskRequest request) {
        Objects.requireNonNull(request, "request");
        return new AgentTaskSnapshot(request.taskId(), request.agentId(), request.type(), request.description(),
                request.parentSessionId(), request.parentTurnId(), request.cwd(), AgentTaskStatus.QUEUED,
                request.submittedAt(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    public AgentTaskSnapshot transitionTo(AgentTaskStatus target,
                                          Instant timestamp,
                                          Optional<String> nextOutput,
                                          Optional<String> nextError) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(nextOutput, "nextOutput");
        Objects.requireNonNull(nextError, "nextError");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Illegal task transition: " + status + " -> " + target);
        }

        Optional<Instant> nextStartedAt = target == AgentTaskStatus.RUNNING
                ? Optional.of(timestamp)
                : startedAt;
        Optional<Instant> nextCompletedAt = target.isTerminal()
                ? Optional.of(timestamp)
                : Optional.empty();
        return new AgentTaskSnapshot(taskId, agentId, type, description, parentSessionId, parentTurnId, cwd,
                target, submittedAt, nextStartedAt, nextCompletedAt, nextOutput, nextError, false);
    }

    public AgentTaskSnapshot markNotificationDelivered() {
        if (!status.isTerminal()) {
            throw new IllegalStateException("Only terminal tasks can deliver notifications");
        }
        if (notificationDelivered) {
            return this;
        }
        return new AgentTaskSnapshot(taskId, agentId, type, description, parentSessionId, parentTurnId, cwd,
                status, submittedAt, startedAt, completedAt, output, error, true);
    }

    private static void validateLifecycle(AgentTaskStatus status,
                                          Optional<Instant> startedAt,
                                          Optional<Instant> completedAt,
                                          boolean notificationDelivered) {
        if (status == AgentTaskStatus.QUEUED && (startedAt.isPresent() || completedAt.isPresent())) {
            throw new IllegalArgumentException("QUEUED task cannot have lifecycle timestamps");
        }
        if (status == AgentTaskStatus.RUNNING && (startedAt.isEmpty() || completedAt.isPresent())) {
            throw new IllegalArgumentException("RUNNING task requires startedAt and no completedAt");
        }
        if (status.isTerminal() && completedAt.isEmpty()) {
            throw new IllegalArgumentException(status + " task requires completedAt");
        }
        if (!status.isTerminal() && notificationDelivered) {
            throw new IllegalArgumentException("Non-terminal task cannot have a delivered notification");
        }
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
