package minicode.core.message;

import java.util.List;

/**
 * 在一个 turn 的模型 step 开始前提供待注入的临时消息。
 *
 * <p>实现方按 session 和 turn 隔离消息，并在 {@link #drain(String, String)} 调用时
 * 原子地取走当前可用通知。AgentLoop 会在后续 step 再次调用，因此运行期间完成的
 * 后台任务也能进入当前 turn。</p>
 */
@FunctionalInterface
public interface TurnMessageSource {
    /**
     * 取走当前 session/turn 可见的全部待处理通知。
     *
     * @param sessionId 当前会话标识
     * @param turnId 当前 turn 标识
     * @return 待注入通知；没有通知时返回空列表
     */
    List<ChatMessage> drain(String sessionId, String turnId);

    static TurnMessageSource noOp() {
        return (sessionId, turnId) -> List.of();
    }
}
