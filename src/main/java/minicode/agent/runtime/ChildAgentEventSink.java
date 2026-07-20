package minicode.agent.runtime;

import minicode.agent.event.AgentTaskEvent;
import minicode.agent.event.AgentTaskEventSink;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentTaskRequest;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;

import java.util.Objects;
import java.util.Optional;

/** 转发可观测的子 Agent 工具进度，同时不把子事件加入父 Turn 事件流。 */
final class ChildAgentEventSink implements AgentEventSink {
    private final AgentTaskRequest request;
    private final AgentTaskEventSink target;

    ChildAgentEventSink(AgentTaskRequest request, AgentTaskEventSink target) {
        this.request = Objects.requireNonNull(request, "request");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public void onEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        switch (event) {
            case AgentEvent.ToolStartedEvent started -> publish(new AgentTaskEvent.ToolStartedEvent(
                    request.agentId(), eventTaskId(), request.parentTurnId(), request.type(), started.timestamp(),
                    started.toolUseId(), started.toolName()));
            case AgentEvent.ToolFinishedEvent finished -> publish(new AgentTaskEvent.ToolFinishedEvent(
                    request.agentId(), eventTaskId(), request.parentTurnId(), request.type(), finished.timestamp(),
                    finished.toolUseId(), finished.toolName(), finished.error()));
            default -> {
                // 生命周期状态由任务管理器负责，其他核心事件仅保留在子 Agent 内部。
            }
        }
    }

    private Optional<String> eventTaskId() {
        return request.runMode() == AgentRunMode.BACKGROUND
                ? Optional.of(request.taskId())
                : Optional.empty();
    }

    private void publish(AgentTaskEvent event) {
        try {
            target.onEvent(event);
        } catch (RuntimeException ignored) {
            // 进度上报仅用于观测，不能导致子 Agent 运行失败。
        }
    }
}
