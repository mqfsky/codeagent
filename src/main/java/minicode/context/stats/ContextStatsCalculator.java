package minicode.context.stats;

import minicode.context.accounting.TokenAccountingResult;
import minicode.context.accounting.TokenAccountingService;
import minicode.core.message.ChatMessage;

import java.util.List;
import java.util.Objects;

public final class ContextStatsCalculator {
    private final TokenAccountingService accountingService;
    private final ModelContextWindow window;

    public ContextStatsCalculator(TokenAccountingService accountingService, ModelContextWindow window) {
        this.accountingService = Objects.requireNonNull(accountingService, "accountingService");
        this.window = Objects.requireNonNull(window, "window");
    }

    /**
     * 计算当前消息列表对模型上下文窗口的占用情况。
     *
     * <p>先统计消息已经消耗的 token，再除以扣除模型输出预留空间后的有效输入上限，
     * 得到上下文占用率，并根据占用率生成对应的告警等级。</p>
     *
     * @param messages 需要纳入上下文统计的消息列表
     * @return 包含 token 用量、窗口配置、占用率和告警等级的上下文统计结果
     */
    public ContextStats calculate(List<ChatMessage> messages) {
        // 优先使用供应商返回的 token usage；没有可信 usage 时由 accountingService 估算。
        TokenAccountingResult accounting = accountingService.account(messages);

        // 有效输入上限 = 模型上下文窗口 - 为本次模型输出预留的 token 空间。
        long effectiveInput = window.effectiveInput();

        // 将已用 token 除以有效输入上限，并把展示用占用率封顶为 100%。
        double utilization = Math.min(1.0d, (double) accounting.totalTokens() / (double) effectiveInput);

        // 汇总窗口参数，并根据占用率计算 NORMAL、WARNING、CRITICAL 或 BLOCKED 告警等级。
        return new ContextStats(accounting, window.contextWindow(), window.outputReserve(), effectiveInput,
                utilization, warningLevel(utilization));
    }

    private static ContextWarningLevel warningLevel(double utilization) {
        if (utilization >= 0.95d) {
            return ContextWarningLevel.BLOCKED;
        }
        if (utilization >= 0.85d) {
            return ContextWarningLevel.CRITICAL;
        }
        if (utilization >= 0.50d) {
            return ContextWarningLevel.WARNING;
        }
        return ContextWarningLevel.NORMAL;
    }
}
