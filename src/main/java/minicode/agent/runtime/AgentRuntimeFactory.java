package minicode.agent.runtime;

import minicode.agent.event.AgentTaskEvent;
import minicode.agent.event.AgentTaskEventSink;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskStatus;
import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.AutoCompactController;
import minicode.context.manager.ContextManager;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.core.loop.AgentLoop;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.UserMessage;
import minicode.core.turn.CancellationToken;
import minicode.prompt.SubAgentPromptBuilder;
import minicode.tools.registry.ToolRegistry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 为每次子 Agent 委派请求创建全新且相互隔离的运行时组件图。 */
public final class AgentRuntimeFactory {
    private static final ModelContextWindow DEFAULT_CONTEXT_WINDOW = new ModelContextWindow(128_000, 8_000);

    private final ToolRegistry parentToolRegistry;
    private final ChildToolRegistryFactory childToolRegistryFactory;
    private final ModelAdapterFactory modelAdapterFactory;
    private final SubAgentPromptBuilder promptBuilder;
    private final Supplier<ContextManager> contextManagerFactory;
    private final Supplier<ContextStatsCalculator> contextStatsFactory;
    private final Supplier<AutoCompactController> autoCompactControllerFactory;
    private final AgentTaskEventSink eventSink;
    private final AgentRunResultMapper resultMapper;

    public AgentRuntimeFactory(ToolRegistry parentToolRegistry, ModelAdapterFactory modelAdapterFactory) {
        this(parentToolRegistry, modelAdapterFactory, AgentTaskEventSink.noOp());
    }

    public AgentRuntimeFactory(ToolRegistry parentToolRegistry,
                               ModelAdapterFactory modelAdapterFactory,
                               AgentTaskEventSink eventSink) {
        this(parentToolRegistry, new ChildToolRegistryFactory(), modelAdapterFactory,
                new SubAgentPromptBuilder(), ContextManager::noOp,
                AgentRuntimeFactory::createDefaultContextStats,
                AutoCompactController::disabled, eventSink, new AgentRunResultMapper());
    }

    /**
     * 使用完整的运行时依赖创建子 Agent 运行时工厂。
     *
     * <p>该构造方法保留所有组件的注入入口，便于应用装配和测试场景自定义
     * 子 Agent 的工具过滤、模型适配器、提示词、上下文管理、自动压缩、
     * 生命周期事件和运行结果映射逻辑。每次创建子 Agent 时，需要隔离的有状态组件
     * 都会通过对应的工厂或 {@link Supplier} 重新创建。</p>
     *
     * @param parentToolRegistry 父 Agent 的工具注册表，用于生成经角色策略过滤的子工具集合
     * @param childToolRegistryFactory 负责构建隔离子工具注册表的工厂
     * @param modelAdapterFactory 根据子工具注册表创建模型适配器的工厂
     * @param promptBuilder 负责构建子 Agent 专用系统提示词的构建器
     * @param contextManagerFactory 为每次子 Agent 运行创建独立上下文管理器的工厂
     * @param contextStatsFactory 为每次子 Agent 运行创建上下文统计器的工厂
     * @param autoCompactControllerFactory 为每次子 Agent 运行创建自动压缩控制器的工厂
     * @param eventSink 接收子 Agent 状态变化和工具执行事件的出口
     * @param resultMapper 将核心 Agent 循环结果转换为子 Agent 运行结果的映射器
     * @throws NullPointerException 当任一构造参数为 {@code null} 时抛出
     */
    public AgentRuntimeFactory(ToolRegistry parentToolRegistry,
                               ChildToolRegistryFactory childToolRegistryFactory,
                               ModelAdapterFactory modelAdapterFactory,
                               SubAgentPromptBuilder promptBuilder,
                               Supplier<ContextManager> contextManagerFactory,
                               Supplier<ContextStatsCalculator> contextStatsFactory,
                               Supplier<AutoCompactController> autoCompactControllerFactory,
                               AgentTaskEventSink eventSink,
                               AgentRunResultMapper resultMapper) {
        this.parentToolRegistry = Objects.requireNonNull(parentToolRegistry, "parentToolRegistry");
        this.childToolRegistryFactory = Objects.requireNonNull(childToolRegistryFactory, "childToolRegistryFactory");
        this.modelAdapterFactory = Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.contextManagerFactory = Objects.requireNonNull(contextManagerFactory, "contextManagerFactory");
        this.contextStatsFactory = Objects.requireNonNull(contextStatsFactory, "contextStatsFactory");
        this.autoCompactControllerFactory = Objects.requireNonNull(autoCompactControllerFactory,
                "autoCompactControllerFactory");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper");
    }

    public AgentRuntime create(AgentTaskRequest request) {
        return create(request, CancellationToken.create());
    }

    /**
     * 创建绑定到任务管理器取消令牌的子 Agent 运行时，
     * 使循环的协作式取消和线程中断共用同一个生命周期控制句柄。
     */
    public AgentRuntime create(AgentTaskRequest request, CancellationToken cancellationToken) {
        AgentTaskRequest actualRequest = Objects.requireNonNull(request, "request");
        CancellationToken actualCancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        // 确定子 agent 角色，加载子 agent 的不可变配置
        AgentSpec spec = AgentSpec.forType(actualRequest.type());

        // 载入子 agent 能够使用的工具
        ToolRegistry childTools = childToolRegistryFactory.create(
                parentToolRegistry, spec, actualRequest.runMode());

        // 用过滤后的子工具表创建模型适配器，保证发给模型的 Tool Schema
        // 与子 Agent 实际可执行的工具保持一致；工厂返回 null 时立即失败。
        ModelAdapter adapter = Objects.requireNonNull(modelAdapterFactory.create(childTools),
                "modelAdapterFactory returned null");

        // 为本次子 Agent 运行创建独立的上下文管理器，不与父 Agent 共享工具结果和压缩状态。
        ContextManager contextManager = Objects.requireNonNull(contextManagerFactory.get(),
                "contextManagerFactory returned null");

        // 创建子 Agent 专用的上下文统计器，用于计算 Token 用量和上下文窗口压力。
        ContextStatsCalculator contextStats = Objects.requireNonNull(contextStatsFactory.get(),
                "contextStatsFactory returned null");

        // 为子 Agent 创建独立的自动压缩控制器，在上下文接近上限时管理压缩流程。
        AutoCompactController autoCompactController = Objects.requireNonNull(autoCompactControllerFactory.get(),
                "autoCompactControllerFactory returned null");

        // 把子 Agent 产生的核心事件转换为带父 Turn、子 Agent 类型等信息的任务事件。
        // 同步任务可以向父 UI 转发工具进度，后台任务则不转发中间进度。
        ChildAgentEventSink childEventSink = new ChildAgentEventSink(actualRequest, eventSink);

        // 将模型、事件、工具、上下文和自动压缩组装成子 Agent 的核心执行循环。
        // AgentCompletionGuard 会拒绝格式异常或明显没有收敛的最终回答。
        AgentLoop agentLoop = new AgentLoop(adapter, childEventSink, childTools, contextManager,
                contextStats, autoCompactController, AgentCompletionGuard.INSTANCE);

        Path cwd = Path.of(actualRequest.cwd()).toAbsolutePath().normalize();
        // 构建上下文
        List<ChatMessage> messages = List.of(
                new SystemMessage(promptBuilder.build(cwd, spec, actualRequest.runMode(), childTools)), // 子 agent 专用提示词
                new UserMessage(actualRequest.prompt()) // 父 Agent 委派的 prompt
        );
        return new AgentRuntime(actualRequest, spec, childTools, adapter, contextManager,
                autoCompactController, agentLoop, actualCancellationToken, messages, resultMapper);
    }

    public AgentRunResult run(AgentTaskRequest request) {
        return run(request, CancellationToken.create());
    }

    // 调用 execute 之后，走到这里
    public AgentRunResult run(AgentTaskRequest request, CancellationToken cancellationToken) {
        AgentTaskRequest actualRequest = Objects.requireNonNull(request, "request");
        if (actualRequest.runMode() != AgentRunMode.SYNC) {
            return create(actualRequest, cancellationToken).run();
        }

        // 更新 UI
        publishSyncState(actualRequest, Optional.empty(), AgentTaskStatus.RUNNING);
        try {
            // 这里开始创建 loop 后，执行 run
            AgentRunResult result = create(actualRequest, cancellationToken).run();

            AgentTaskStatus terminal = result.cancelled()
                    ? AgentTaskStatus.CANCELLED
                    : result.error().isPresent() ? AgentTaskStatus.FAILED : AgentTaskStatus.COMPLETED;
            publishSyncState(actualRequest, Optional.of(AgentTaskStatus.RUNNING), terminal);
            return result;
        } catch (RuntimeException exception) {
            publishSyncState(actualRequest, Optional.of(AgentTaskStatus.RUNNING), AgentTaskStatus.FAILED);
            throw exception;
        }
    }

    private void publishSyncState(AgentTaskRequest request,
                                  Optional<AgentTaskStatus> previous,
                                  AgentTaskStatus status) {
        try {
            eventSink.onEvent(new AgentTaskEvent.StateChangedEvent(
                    request.agentId(), Optional.empty(), request.parentTurnId(), request.type(), Instant.now(),
                    previous, status));
        } catch (RuntimeException ignored) {
            // 生命周期渲染仅用于观测，不能导致委派失败。
        }
    }

    private static ContextStatsCalculator createDefaultContextStats() {
        return new ContextStatsCalculator(new TokenAccountingService(), DEFAULT_CONTEXT_WINDOW);
    }
}
