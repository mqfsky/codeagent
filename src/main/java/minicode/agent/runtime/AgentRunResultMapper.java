package minicode.agent.runtime;

import minicode.agent.model.AgentRunResult;
import minicode.core.message.AssistantMessage;
import minicode.core.message.ChatMessage;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.core.turn.CancellationDetails;
import minicode.core.turn.EmptyFallbackDetails;
import minicode.core.turn.ModelErrorDetails;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 将核心循环结果转换为稳定的子 Agent 运行时结果契约。 */
public final class AgentRunResultMapper {
    private static final String EMPTY_OUTPUT = "(agent produced no output)";

    public AgentRunResult map(AgentTurnResult turnResult) {
        AgentTurnResult result = Objects.requireNonNull(turnResult, "turnResult");
        Optional<String> finalOutput = result.stopReason() == AgentTurnStopReason.FINAL
                ? terminalFinalOutput(result.messages())
                : Optional.empty();
        String output = finalOutput.orElse(EMPTY_OUTPUT);
        String reason = result.stopReason().name();
        return switch (result.stopReason()) {
            case FINAL -> finalOutput.isPresent()
                    ? AgentRunResult.completed(output, reason)
                    : AgentRunResult.failed(output, reason, "Agent completed without a final response");
            case MAX_STEPS -> AgentRunResult.failed(output, reason,
                    "Child agent reached maximum steps");
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

    private static Optional<String> terminalFinalOutput(List<ChatMessage> messages) {
        if (!messages.isEmpty()
                && messages.getLast() instanceof AssistantMessage assistant
                && !assistant.content().isBlank()) {
            return Optional.of(assistant.content());
        }
        return Optional.empty();
    }
}
