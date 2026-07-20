package minicode.agent.task;

/** 接收后台任务会超出配置容量时抛出。 */
public final class AgentTaskRejectedException extends IllegalStateException {
    public AgentTaskRejectedException(String message) {
        super(message);
    }
}
