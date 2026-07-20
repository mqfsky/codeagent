package minicode.tools.builtin;

import minicode.tools.api.Tool;
import minicode.tools.api.ToolValidationErrorHandler;
import minicode.tools.result.ToolResult;

import java.util.List;

/** 为公开的 agent/task 工具提供统一、稳定的 JSON 校验错误响应。 */
interface AgentTaskJsonTool extends Tool, ToolValidationErrorHandler {
    @Override
    default ToolResult validationError(List<String> errors) {
        String message = errors == null || errors.isEmpty()
                ? "Invalid tool input"
                : String.join("; ", errors);
        return ToolResult.error(AgentTaskToolJson.error("INVALID_ARGUMENT", message));
    }
}
