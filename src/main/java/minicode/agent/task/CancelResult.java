package minicode.agent.task;

import minicode.agent.model.AgentTaskSnapshot;

import java.util.Objects;

/** 幂等的取消结果。 */
public record CancelResult(AgentTaskSnapshot snapshot, boolean changed) {
    public CancelResult {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
}
