package minicode.tools.api;

import minicode.tools.result.ToolResult;

import java.util.List;

/** 在 Registry 层输入校验失败时，允许工具保持其公开响应格式。 */
@FunctionalInterface
public interface ToolValidationErrorHandler {
    ToolResult validationError(List<String> errors);
}
