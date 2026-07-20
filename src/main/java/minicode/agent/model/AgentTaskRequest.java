package minicode.agent.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 传递给子 Agent 运行时的不可变启动请求。 */
public record AgentTaskRequest(String taskId,
                               String agentId,
                               AgentType type,
                               String description,
                               String prompt,
                               String parentSessionId,
                               String parentTurnId,
                               String cwd,
                               AgentRunMode runMode,
                               Instant submittedAt) {
    public AgentTaskRequest {
        taskId = requireText(taskId, "taskId");
        agentId = requireText(agentId, "agentId");
        type = Objects.requireNonNull(type, "type");
        description = requireText(description, "description");
        prompt = requireText(prompt, "prompt");
        parentSessionId = requireText(parentSessionId, "parentSessionId");
        parentTurnId = requireText(parentTurnId, "parentTurnId");
        cwd = requireText(cwd, "cwd");
        runMode = Objects.requireNonNull(runMode, "runMode");
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
    }

    public static AgentTaskRequest create(AgentType type,
                                          String description,
                                          String prompt,
                                          String parentSessionId,
                                          String parentTurnId,
                                          String cwd,
                                          AgentRunMode runMode) {
        String taskId = UUID.randomUUID().toString();
        return new AgentTaskRequest(taskId, "agent-" + taskId, type, description, prompt,
                parentSessionId, parentTurnId, cwd, runMode, Instant.now());
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
