package minicode.agent.task;

import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.core.turn.CancellationToken;

@FunctionalInterface
public interface AgentTaskExecutor {
    AgentRunResult execute(AgentTaskRequest request, CancellationToken cancellationToken) throws Exception;
}
