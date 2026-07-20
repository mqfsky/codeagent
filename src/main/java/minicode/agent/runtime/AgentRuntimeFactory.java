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
import java.util.function.Function;
import java.util.function.Supplier;

/** 为每次子 Agent 委派请求创建全新且相互隔离的运行时组件图。 */
public final class AgentRuntimeFactory {
    private static final ModelContextWindow DEFAULT_CONTEXT_WINDOW = new ModelContextWindow(128_000, 8_000);

    private final Function<AgentRunMode, ToolRegistry> sourceToolRegistryFactory;
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
        this(fixedSource(parentToolRegistry),
                new ChildToolRegistryFactory(), modelAdapterFactory,
                new SubAgentPromptBuilder(), ContextManager::noOp,
                () -> new ContextStatsCalculator(new TokenAccountingService(), DEFAULT_CONTEXT_WINDOW),
                AutoCompactController::disabled, eventSink, new AgentRunResultMapper());
    }

    /**
     * 创建可按运行模式提供不同基础工具的工厂，使应用装配层能够为后台任务提供由拒绝式、
     * 无交互权限服务保护的安全工具实例。
     */
    public AgentRuntimeFactory(Function<AgentRunMode, ToolRegistry> sourceToolRegistryFactory,
                               ModelAdapterFactory modelAdapterFactory,
                               AgentTaskEventSink eventSink) {
        this(sourceToolRegistryFactory, new ChildToolRegistryFactory(), modelAdapterFactory,
                new SubAgentPromptBuilder(), ContextManager::noOp,
                () -> new ContextStatsCalculator(new TokenAccountingService(), DEFAULT_CONTEXT_WINDOW),
                AutoCompactController::disabled, eventSink, new AgentRunResultMapper());
    }

    public AgentRuntimeFactory(ToolRegistry parentToolRegistry,
                               ChildToolRegistryFactory childToolRegistryFactory,
                               ModelAdapterFactory modelAdapterFactory,
                               SubAgentPromptBuilder promptBuilder,
                               Supplier<ContextManager> contextManagerFactory,
                               Supplier<ContextStatsCalculator> contextStatsFactory,
                               Supplier<AutoCompactController> autoCompactControllerFactory,
                               AgentTaskEventSink eventSink,
                               AgentRunResultMapper resultMapper) {
        this(fixedSource(parentToolRegistry),
                childToolRegistryFactory, modelAdapterFactory, promptBuilder, contextManagerFactory,
                contextStatsFactory, autoCompactControllerFactory, eventSink, resultMapper);
    }

    public AgentRuntimeFactory(Function<AgentRunMode, ToolRegistry> sourceToolRegistryFactory,
                               ChildToolRegistryFactory childToolRegistryFactory,
                               ModelAdapterFactory modelAdapterFactory,
                               SubAgentPromptBuilder promptBuilder,
                               Supplier<ContextManager> contextManagerFactory,
                               Supplier<ContextStatsCalculator> contextStatsFactory,
                               Supplier<AutoCompactController> autoCompactControllerFactory,
                               AgentTaskEventSink eventSink,
                               AgentRunResultMapper resultMapper) {
        this.sourceToolRegistryFactory = Objects.requireNonNull(sourceToolRegistryFactory,
                "sourceToolRegistryFactory");
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
        AgentSpec spec = AgentSpec.forType(actualRequest.type());
        ToolRegistry sourceTools = Objects.requireNonNull(
                sourceToolRegistryFactory.apply(actualRequest.runMode()),
                "sourceToolRegistryFactory returned null");
        ToolRegistry childTools = childToolRegistryFactory.create(
                sourceTools, spec, actualRequest.runMode());
        ModelAdapter adapter = Objects.requireNonNull(modelAdapterFactory.create(childTools),
                "modelAdapterFactory returned null");
        ContextManager contextManager = Objects.requireNonNull(contextManagerFactory.get(),
                "contextManagerFactory returned null");
        ContextStatsCalculator contextStats = Objects.requireNonNull(contextStatsFactory.get(),
                "contextStatsFactory returned null");
        AutoCompactController autoCompactController = Objects.requireNonNull(autoCompactControllerFactory.get(),
                "autoCompactControllerFactory returned null");
        ChildAgentEventSink childEventSink = new ChildAgentEventSink(actualRequest, eventSink);
        AgentLoop agentLoop = new AgentLoop(adapter, childEventSink, childTools, contextManager,
                contextStats, autoCompactController);

        Path cwd = Path.of(actualRequest.cwd()).toAbsolutePath().normalize();
        List<ChatMessage> messages = List.of(
                new SystemMessage(promptBuilder.build(cwd, spec, actualRequest.runMode(), childTools)),
                new UserMessage(actualRequest.prompt())
        );
        return new AgentRuntime(actualRequest, spec, childTools, adapter, contextManager,
                autoCompactController, agentLoop, actualCancellationToken, messages, resultMapper);
    }

    public AgentRunResult run(AgentTaskRequest request) {
        return run(request, CancellationToken.create());
    }

    public AgentRunResult run(AgentTaskRequest request, CancellationToken cancellationToken) {
        AgentTaskRequest actualRequest = Objects.requireNonNull(request, "request");
        if (actualRequest.runMode() != AgentRunMode.SYNC) {
            return create(actualRequest, cancellationToken).run();
        }

        publishSyncState(actualRequest, Optional.empty(), AgentTaskStatus.RUNNING);
        try {
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

    private static Function<AgentRunMode, ToolRegistry> fixedSource(ToolRegistry parentToolRegistry) {
        ToolRegistry actualParent = Objects.requireNonNull(parentToolRegistry, "parentToolRegistry");
        return ignored -> actualParent;
    }
}
