package minicode.agent.runtime;

import minicode.agent.model.AgentRunResult;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.ChatMessage;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将核心循环结果转换为稳定的子 Agent 运行时结果契约。 */
public final class AgentRunResultMapper {
    private static final String EMPTY_OUTPUT = "(agent produced no output)";

    public AgentRunResult map(AgentTurnResult turnResult) {
        AgentTurnResult result = Objects.requireNonNull(turnResult, "turnResult");
        String output = assistantOutput(result.messages());
        String reason = result.stopReason().name();
        return switch (result.stopReason()) {
            case FINAL, MAX_STEPS -> AgentRunResult.completed(output, reason);
            case MODEL_ERROR -> AgentRunResult.failed(output, reason,
                    ((ModelErrorDetails) result.stopDetails().orElseThrow()).error().message());
            case CANCELLED -> AgentRunResult.failed(output, reason,
                    ((CancellationDetails) result.stopDetails().orElseThrow()).cancellation().reason());
            case AWAIT_USER -> AgentRunResult.failed(output, reason,
                    "Child agents cannot wait for interactive user input");
            case EMPTY_RESPONSE_FALLBACK -> AgentRunResult.failed(output, reason,
                    result.stopDetails()
                            .map(EmptyFallbackDetails.class::cast)
                            .flatMap(EmptyFallbackDetails::reason)
                            .orElse("Child agent produced no usable response"));
        };
    }

    private static String assistantOutput(List<ChatMessage> messages) {
        List<String> parts = new ArrayList<>();
        for (ChatMessage message : messages) {
            String content = switch (message) {
                case AssistantMessage assistant -> assistant.content();
                case AssistantProgressMessage progress -> progress.content();
                default -> "";
            };
            if (!content.isBlank()) {
                parts.add(content);
            }
        }
        return parts.isEmpty() ? EMPTY_OUTPUT : String.join("\n\n", parts);
    }
}
