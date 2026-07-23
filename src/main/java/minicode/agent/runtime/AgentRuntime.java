package minicode.agent.runtime;

import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.context.compact.AutoCompactController;
import minicode.context.manager.ContextManager;
import minicode.core.loop.AgentLoop;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.CancellationToken;
import minicode.tools.registry.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单次子 Agent 执行所需的全部隔离组件和输入状态。 */
public final class AgentRuntime {
    private final AgentTaskRequest request;
    private final AgentSpec spec;
    private final ToolRegistry toolRegistry;
    private final ModelAdapter modelAdapter;
    private final ContextManager contextManager;
    private final AutoCompactController autoCompactController;
    private final AgentLoop agentLoop;
    private final CancellationToken cancellationToken;
    private final List<ChatMessage> initialMessages;
    private final AgentRunResultMapper resultMapper;
    private final AtomicBoolean started = new AtomicBoolean();

    AgentRuntime(AgentTaskRequest request,
                 AgentSpec spec,
                 ToolRegistry toolRegistry,
                 ModelAdapter modelAdapter,
                 ContextManager contextManager,
                 AutoCompactController autoCompactController,
                 AgentLoop agentLoop,
                 CancellationToken cancellationToken,
                 List<ChatMessage> initialMessages,
                 AgentRunResultMapper resultMapper) {
        this.request = Objects.requireNonNull(request, "request");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager");
        this.autoCompactController = Objects.requireNonNull(autoCompactController, "autoCompactController");
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.initialMessages = List.copyOf(Objects.requireNonNull(initialMessages, "initialMessages"));
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper");
    }

    // 真正执行
    public AgentRunResult run() {
        // 保证一个 AgentRuntime 实例只能执行一次。
        // cap 如果是 false 就设置成 true
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("AgentRuntime can only be run once");
        }
        // 将结果经过映射转换后返回
        return resultMapper.map(agentLoop.runTurn(turnRequest()));
    }

    public AgentTurnRequest turnRequest() {
        return new AgentTurnRequest(
                request.agentId(),
                Path.of(request.cwd()).toAbsolutePath().normalize(),
                request.parentSessionId(),
                initialMessages,
                spec.maxSteps(),
                Optional.empty(),
                cancellationToken
        );
    }

    public AgentTaskRequest request() { return request; }
    public AgentSpec spec() { return spec; }
    public ToolRegistry toolRegistry() { return toolRegistry; }
    public ModelAdapter modelAdapter() { return modelAdapter; }
    public ContextManager contextManager() { return contextManager; }
    public AutoCompactController autoCompactController() { return autoCompactController; }
    public AgentLoop agentLoop() { return agentLoop; }
    public CancellationToken cancellationToken() { return cancellationToken; }
    public List<ChatMessage> initialMessages() { return initialMessages; }
}
