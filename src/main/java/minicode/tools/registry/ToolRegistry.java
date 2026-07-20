package minicode.tools.registry;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.tools.result.ToolResult;
import minicode.tools.api.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

public final class ToolRegistry implements ToolExecutor {
    // 内部维护一个 map
    private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

    // 注册
    public void register(Tool tool) {
        Tool actualTool = Objects.requireNonNull(tool, "tool");
        String name = actualTool.metadata().name();
        if (toolsByName.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        // name 作为 key
        toolsByName.put(name, actualTool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(toolsByName.get(Objects.requireNonNull(name, "name")));
    }

    public List<Tool> list() {
        return List.copyOf(toolsByName.values());
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext toolContext) {
        // 校验参数
        ToolCall actualCall = Objects.requireNonNull(call, "call");
        ToolContext actualToolContext = Objects.requireNonNull(toolContext, "toolContext");
        actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

        // 获取工具
        Tool tool = toolsByName.get(actualCall.toolName());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + actualCall.toolName());
        }

        ValidationResult validation;
        try {
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

            // 校验输入
            validation = tool.validateInput(actualCall.input());

            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return validationError(tool, List.of(messageOrDefault(exception, "Tool input validation failed")));
        }

        if (validation == null) {
            return validationError(tool, List.of("validator returned null"));
        }

        if (!validation.valid()) {
            return validationError(tool, validation.errors());
        }

        if (validation.normalizedInput().isEmpty()) {
            return validationError(tool, List.of("valid result requires normalized input"));
        }

        JsonNode normalizedInput = validation.normalizedInput().get();

        try {
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
            // 调用工具，得到结果，允许？拒绝？
            ToolResult result = tool.run(normalizedInput, actualToolContext);
            actualToolContext.cancellationToken().throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

            return result == null ? ToolResult.error("Tool returned null ToolResult") : result;
        } catch (CancellationRequestedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ToolResult.error(messageOrDefault(exception, "Tool execution failed"));
        }
    }

    private static ToolResult validationError(Tool tool, List<String> errors) {
        if (tool instanceof ToolValidationErrorHandler handler) {
            try {
                ToolResult result = handler.validationError(List.copyOf(errors));
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException ignored) {
                // 回退到 Registry 向后兼容的纯文本错误响应。
            }
        }
        return ToolResult.error(formatValidationErrors(tool.metadata().name(), errors));
    }

    private static String formatValidationErrors(String toolName, List<String> errors) {
        StringJoiner joiner = new StringJoiner("; ");
        errors.forEach(joiner::add);
        return "Tool input validation failed for " + toolName + ": " + joiner;
    }

    private static String messageOrDefault(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
