package minicode.core.step;

import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型返回文本类内容时的统一 step。
 *
 * <p>它既可以表示最终回复，也可以表示进展消息或 provider 诊断信息。</p>
 *
 * @param content 模型返回的文本内容
 * @param kind 文本内容的语义类型
 * @param thinkingBlocks provider 返回的 thinking block 列表
 * @param diagnostics provider 响应诊断；为空表示没有诊断
 * @param usage provider 返回的 token 用量；为空表示没有用量信息
 */
public record AssistantStep(String content, AssistantKind kind, List<ProviderThinkingBlock> thinkingBlocks,
                            Optional<StepDiagnostics> diagnostics, Optional<ProviderUsage> usage) implements AgentStep {
    public AssistantStep {
        content = Objects.requireNonNull(content, "content");
        kind = Objects.requireNonNull(kind, "kind");
        thinkingBlocks = List.copyOf(Objects.requireNonNull(thinkingBlocks, "thinkingBlocks"));
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        usage = Objects.requireNonNull(usage, "usage");
    }

    public AssistantStep(String content, AssistantKind kind) {
        this(content, kind, List.of(), Optional.empty(), Optional.empty());
    }
}
