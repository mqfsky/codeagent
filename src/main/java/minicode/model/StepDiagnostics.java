package minicode.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型响应 step 的诊断信息。
 *
 * @param stopReason provider 返回的停止原因；为空表示没有
 * @param blockTypes provider 响应中出现的 block 类型列表
 * @param ignoredBlockTypes 被适配器忽略的 block 类型列表
 */
public record StepDiagnostics(Optional<String> stopReason, List<String> blockTypes, List<String> ignoredBlockTypes) {
    public StepDiagnostics {
        stopReason = Objects.requireNonNull(stopReason, "stopReason");
        blockTypes = List.copyOf(Objects.requireNonNull(blockTypes, "blockTypes"));
        ignoredBlockTypes = List.copyOf(Objects.requireNonNull(ignoredBlockTypes, "ignoredBlockTypes"));
    }

    public static StepDiagnostics empty() {
        return new StepDiagnostics(Optional.empty(), List.of(), List.of());
    }
}
