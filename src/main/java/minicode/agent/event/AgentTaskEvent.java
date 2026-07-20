package minicode.agent.event;

import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 子 Agent 生命周期发出的事件，不改变父 Agent 的 {@code AgentEvent} 契约。 */
public sealed interface AgentTaskEvent permits AgentTaskEvent.StateChangedEvent,
        AgentTaskEvent.ToolStartedEvent, AgentTaskEvent.ToolFinishedEvent {
    String agentId();

    Optional<String> taskId();

    String parentTurnId();

    AgentType agentType();

    Instant timestamp();

    record StateChangedEvent(String agentId,
                             Optional<String> taskId,
                             String parentTurnId,
                             AgentType agentType,
                             Instant timestamp,
                             Optional<AgentTaskStatus> previousStatus,
                             AgentTaskStatus status) implements AgentTaskEvent {
        public StateChangedEvent {
            requireScope(agentId, taskId, parentTurnId, agentType, timestamp);
            previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
            status = Objects.requireNonNull(status, "status");
            if (previousStatus.isPresent() && !previousStatus.orElseThrow().canTransitionTo(status)) {
                throw new IllegalArgumentException("Illegal event transition: "
                        + previousStatus.orElseThrow() + " -> " + status);
            }
        }
    }

    record ToolStartedEvent(String agentId,
                            Optional<String> taskId,
                            String parentTurnId,
                            AgentType agentType,
                            Instant timestamp,
                            String toolUseId,
                            String toolName) implements AgentTaskEvent {
        public ToolStartedEvent {
            requireScope(agentId, taskId, parentTurnId, agentType, timestamp);
            toolUseId = requireText(toolUseId, "toolUseId");
            toolName = requireText(toolName, "toolName");
        }
    }

    record ToolFinishedEvent(String agentId,
                             Optional<String> taskId,
                             String parentTurnId,
                             AgentType agentType,
                             Instant timestamp,
                             String toolUseId,
                             String toolName,
                             boolean error) implements AgentTaskEvent {
        public ToolFinishedEvent {
            requireScope(agentId, taskId, parentTurnId, agentType, timestamp);
            toolUseId = requireText(toolUseId, "toolUseId");
            toolName = requireText(toolName, "toolName");
        }
    }

    private static void requireScope(String agentId,
                                     Optional<String> taskId,
                                     String parentTurnId,
                                     AgentType agentType,
                                     Instant timestamp) {
        requireText(agentId, "agentId");
        Objects.requireNonNull(taskId, "taskId").ifPresent(value -> requireText(value, "taskId"));
        requireText(parentTurnId, "parentTurnId");
        Objects.requireNonNull(agentType, "agentType");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
