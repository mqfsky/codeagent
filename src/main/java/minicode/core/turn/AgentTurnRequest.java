package minicode.core.turn;

import minicode.core.message.ChatMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 * Agent 执行一轮 turn 时需要的完整请求上下文。
 *
 * <p>它把当前工作目录、会话 id、历史消息、步数限制和取消控制统一传入 {@link minicode.core.loop.AgentLoop}。</p>
 *
 * @param turnId 本轮 turn 的唯一标识
 * @param cwd 本轮工具执行和路径解析使用的工作目录
 * @param sessionId 当前会话 id
 * @param messages 发给模型的消息列表
 * @param maxSteps 本轮最多允许执行的模型/工具 step 数量
 * @param modelName 本轮指定的模型名称；为空表示使用默认模型
 * @param cancellationToken 本轮取消控制令牌
 */
public record AgentTurnRequest(
        String turnId,
        Path cwd,
        String sessionId,
        List<ChatMessage> messages,
        int maxSteps,
        Optional<String> modelName,
        CancellationToken cancellationToken
) {
    public AgentTurnRequest(String turnId, Path cwd, String sessionId, List<ChatMessage> messages, int maxSteps,
                            Optional<String> modelName) {
        this(turnId, cwd, sessionId, messages, maxSteps, modelName, CancellationToken.none());
    }

    public AgentTurnRequest {
        if (Objects.requireNonNull(turnId, "turnId").isBlank()) {
            throw new IllegalArgumentException("turnId must not be blank");
        }
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        modelName = Objects.requireNonNull(modelName, "modelName");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }
}
