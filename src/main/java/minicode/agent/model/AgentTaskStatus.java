package minicode.agent.model;

import java.util.EnumSet;
import java.util.Set;

/** 单个后台子 Agent 在当前进程内的生命周期状态。 */
public enum AgentTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<AgentTaskStatus> TERMINAL = EnumSet.of(
            COMPLETED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(AgentTaskStatus target) {
        if (target == null || isTerminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target.isTerminal();
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }
}
