package minicode.agent.model;

import java.util.Objects;
import java.util.Optional;

/** 与具体模型供应商 Turn 结果类型解耦的运行结果。 */
public record AgentRunResult(String output, String stopReason, Optional<String> error) {
    public AgentRunResult {
        output = Objects.requireNonNull(output, "output");
        stopReason = requireText(stopReason, "stopReason");
        error = Objects.requireNonNull(error, "error");
        error = error.map(value -> requireText(value, "error"));
    }

    public static AgentRunResult completed(String output, String stopReason) {
        return new AgentRunResult(output, stopReason, Optional.empty());
    }

    public static AgentRunResult failed(String output, String stopReason, String error) {
        return new AgentRunResult(output, stopReason, Optional.of(error));
    }

    public boolean successful() {
        return error.isEmpty() && !cancelled();
    }

    public boolean cancelled() {
        return "CANCELLED".equalsIgnoreCase(stopReason);
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
