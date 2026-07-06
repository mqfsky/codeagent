package minicode.core.step;

import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;
import minicode.tools.api.ToolCall;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型请求调用一个或多个工具时的统一 step。
 *
 * <p>AgentLoop 会先记录工具调用消息，再执行工具，并把工具结果回填到后续模型请求中。</p>
 *
 * @param calls 本次 step 中模型请求的工具调用列表
 * @param content 工具调用同时携带的文本内容；为空表示没有文本
 * @param contentKind 文本内容的语义类型
 * @param thinkingBlocks provider 返回的 thinking block 列表
 * @param diagnostics provider 响应诊断；为空表示没有诊断
 * @param usage provider 返回的 token 用量；为空表示没有用量信息
 */
public record ToolCallsStep(List<ToolCall> calls, Optional<String> content, ContentKind contentKind,
                            List<ProviderThinkingBlock> thinkingBlocks, Optional<StepDiagnostics> diagnostics,
                            Optional<ProviderUsage> usage) implements AgentStep {
    public ToolCallsStep {
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("tool calls step requires at least one call");
        }
        content = Objects.requireNonNull(content, "content");
        contentKind = Objects.requireNonNull(contentKind, "contentKind");
        thinkingBlocks = List.copyOf(Objects.requireNonNull(thinkingBlocks, "thinkingBlocks"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        usage = Objects.requireNonNull(usage, "usage");
    }
}
