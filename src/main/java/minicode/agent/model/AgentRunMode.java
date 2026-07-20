package minicode.agent.model;

/** 委派的 Agent 由父 Turn 同步等待，还是作为后台任务运行。 */
public enum AgentRunMode {
    SYNC,
    BACKGROUND
}
