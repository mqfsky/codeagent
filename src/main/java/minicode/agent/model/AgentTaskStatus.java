package minicode.agent.model;

import java.util.EnumSet;
import java.util.Set;

/** 单个后台 Agent 任务的持久化生命周期状态。 */
public enum AgentTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED;

    private static final Set<AgentTaskStatus> TERMINAL = EnumSet.of(
            COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(AgentTaskStatus target) {
        if (target == null || isTerminal()) {
            return false;
        }
        return switch (this) {
            case QUEUED -> target == RUNNING || target == CANCELLED || target == INTERRUPTED;
            case RUNNING -> target.isTerminal();
            case COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED -> false;
        };
    }
}
