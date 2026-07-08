package minicode.context.compact;

import minicode.context.boundary.ContextBoundaryGuard;
import minicode.context.stats.ContextStats;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

public final class AutoCompactController {
    private final CompactService compactService;
    private final AutoCompactPolicy policy;
    private final boolean enabled;
    private int consecutiveFailures;
    private int cooldownRemaining;

    public AutoCompactController(CompactService compactService, AutoCompactPolicy policy) {
        this(compactService, policy, true);
    }

    private AutoCompactController(CompactService compactService, AutoCompactPolicy policy, boolean enabled) {
        this.compactService = Objects.requireNonNull(compactService, "compactService");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.enabled = enabled;
    }

    public static AutoCompactController disabled() {
        return new AutoCompactController(new CompactService(), AutoCompactPolicy.defaults(), false);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean willAttempt(List<ChatMessage> messages, ContextStats stats) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        return enabled
                && stats.effectiveInput() >= policy.minEffectiveInput() // 有效输入窗口
                && stats.utilization() >= policy.utilizationThreshold() // 上下文占用
                && ContextBoundaryGuard.isCompactSafeBoundary(actualMessages) // 检查工具调用是不是完整闭合
                && cooldownRemaining == 0; // 失败冷却计数器，自动压缩失败后，不要每次模型请求前都立刻再尝试压缩，而是先跳过几轮 preflight
    }

    public AutoCompactResult preflight(List<ChatMessage> messages, ContextStats stats, ModelAdapter modelAdapter) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(modelAdapter, "modelAdapter");

        if (!enabled) {
            return AutoCompactResult.skipped(actualMessages, "auto compact disabled");
        }
        // effectiveInput 太小 skipped
        if (stats.effectiveInput() < policy.minEffectiveInput()) {
            resetFailures();
            return AutoCompactResult.skipped(actualMessages, "effective input window is below auto compact minimum");
        }
        // utilization 低于阈值 skipped
        if (stats.utilization() < policy.utilizationThreshold()) {
            resetFailures();
            return AutoCompactResult.skipped(actualMessages, "context utilization is below auto compact threshold");
        }
        // 工具调用边界不安全 skipped
        if (!ContextBoundaryGuard.isCompactSafeBoundary(actualMessages)) {
            return AutoCompactResult.skipped(actualMessages, "unsafe compact boundary: incomplete tool round");
        }
        // cooldown 中 skipped，避免频繁压缩
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return AutoCompactResult.skipped(actualMessages, "auto compact cooldown after previous failure");
        }

        // 开始压缩
        ManualCompactResult result = compactService.compact(new CompactRequest(actualMessages, modelAdapter,
                CompactTrigger.AUTO));

        // 压缩结果判断
        if (result.status() == CompactStatus.COMPACTED) {
            consecutiveFailures = 0;
            cooldownRemaining = 0;
            // 包装成自动压缩结果
            return AutoCompactResult.compacted(result.messages(), result.boundary().orElseThrow());
        }
        if (result.status() == CompactStatus.FAILED) {
            recordFailure();
            return AutoCompactResult.failed(actualMessages, result.reason().orElse("auto compact failed"));
        }
        return AutoCompactResult.skipped(actualMessages, result.reason().orElse("auto compact skipped"));
    }

    private void recordFailure() {
        consecutiveFailures = Math.min(policy.maxFailures(), consecutiveFailures + 1);
        cooldownRemaining = policy.failureCooldownPreflights() * consecutiveFailures;
    }

    private void resetFailures() {
        consecutiveFailures = 0;
        cooldownRemaining = 0;
    }
}
