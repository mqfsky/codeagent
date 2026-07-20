package minicode.app;

import minicode.agent.event.AgentTaskEventSink;
import minicode.agent.model.AgentRunMode;
import minicode.agent.runtime.AgentRunResultMapper;
import minicode.agent.runtime.AgentRuntimeFactory;
import minicode.agent.runtime.ChildToolRegistryFactory;
import minicode.agent.runtime.ModelAdapterFactory;
import minicode.agent.task.AgentInbox;
import minicode.agent.task.AgentInboxTurnMessageSource;
import minicode.agent.task.AgentTaskStore;
import minicode.agent.task.SubAgentTaskManager;
import minicode.context.manager.ContextManager;
import minicode.context.accounting.TokenAccountingService;
import minicode.context.compact.CompactRequest;
import minicode.context.compact.AutoCompactController;
import minicode.context.compact.AutoCompactPolicy;
import minicode.context.compact.CompactService;
import minicode.context.compact.CompactStatus;
import minicode.context.compact.CompactTrigger;
import minicode.context.compact.ManualCompactResult;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.loop.ForkableModelAdapter;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.loop.ModelAdapter;
import minicode.model.MockModelAdapter;
import minicode.model.ModelContextProfile;
import minicode.model.ModelMetadata;
import minicode.model.ModelMetadataResolver;
import minicode.model.anthropic.AnthropicModelAdapter;
import minicode.model.anthropic.AnthropicModelsApiClient;
import minicode.model.anthropic.HttpAnthropicTransport;
import minicode.model.openai.OpenAIModelAdapter;
import minicode.init.ProjectInitializer;
import minicode.mcp.McpRuntime;
import minicode.mcp.McpServerSummary;
import minicode.memory.MemorySnapshot;
import minicode.mcp.McpToolHydrator;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.permissions.store.JsonPermissionStore;
import minicode.permissions.store.PermissionStore;
import minicode.prompt.SystemPromptBuilder;
import minicode.prompt.SubAgentPromptBuilder;
import minicode.session.factory.SessionEventFactory;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.store.SessionStore;
import minicode.skills.SkillDiscovery;
import minicode.skills.SkillRegistry;
import minicode.tools.builtin.AskUserTool;
import minicode.tools.builtin.AgentTool;
import minicode.tools.builtin.EditFileTool;
import minicode.tools.builtin.GrepFilesTool;
import minicode.tools.builtin.ListFilesTool;
import minicode.tools.builtin.LoadSkillTool;
import minicode.tools.builtin.ModifyFileTool;
import minicode.tools.builtin.PatchFileTool;
import minicode.tools.builtin.ReadFilePathAccess;
import minicode.tools.builtin.ReadFileTool;
import minicode.tools.builtin.RunCommandTool;
import minicode.tools.builtin.TaskCancelTool;
import minicode.tools.builtin.TaskListTool;
import minicode.tools.builtin.TaskOutputTool;
import minicode.tools.builtin.TaskStatusTool;
import minicode.tools.builtin.WriteFileTool;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResultStorage;
import minicode.workspace.WorkspacePathResolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;

/**
 * 应用运行期的核心服务集合，扮演组装中心的角色，把运行 CodeAgent 所需的组件创建出来、连接起来，并统一提供给上层使用
 *
 *
 * @param toolRegistry 应用注册并暴露给模型的工具注册表
 * @param permissionService 执行路径、命令、编辑和 MCP 调用前使用的权限服务
 * @param contextManager 负责大工具输出替换和轻量上下文清理的上下文管理器
 * @param sessionStore 读取和追加 session JSONL 日志的存储组件
 * @param sessionPersistenceRunner 把持久化计划转换成 session 事件的执行器
 * @param agentLoop 负责 model -> tool -> model 主流程的 AgentLoop
 * @param modelAdapter 用于生成压缩摘要的模型适配器
 * @param compactService 执行手动或自动上下文压缩的服务
 * @param systemPromptBuilder 按当前环境生成系统提示词的构建器
 * @param workspacePathResolver 解析和规范化 workspace 路径的组件
 * @param skillRegistry 当前发现的技能注册表
 * @param mcpRuntime 已启动或空的 MCP 运行时
 * @param permissionStore 长期权限决策的存储组件
 * @param permissionStorePath 权限存储文件路径
 * @param home MiniCode 的数据目录
 * @param cwd 当前 workspace 工作目录
 * @param sessionId 当前会话 id
 * @param runtimeConfig 运行配置；为空表示测试或无配置路径
 * @param subAgentTaskManager 后台子 Agent 管理器；手工构造旧服务时为空
 */
public record ApplicationServices(ToolRegistry toolRegistry,
                                  PermissionService permissionService,
                                  ContextManager contextManager,
                                  SessionStore sessionStore,
                                  SessionPersistenceRunner sessionPersistenceRunner,
                                  AgentLoop agentLoop,
                                  ModelAdapter modelAdapter,
                                  CompactService compactService,
                                  SystemPromptBuilder systemPromptBuilder,
                                  WorkspacePathResolver workspacePathResolver,
                                  SkillRegistry skillRegistry,
                                  McpRuntime mcpRuntime,
                                  PermissionStore permissionStore,
                                  Path permissionStorePath,
                                  Path home,
                                  Path cwd,
                                  String sessionId,
                                  Optional<RuntimeConfig> runtimeConfig,
                                  Optional<SubAgentTaskManager> subAgentTaskManager) {
    private static final Duration MODEL_METADATA_TIMEOUT = Duration.ofSeconds(3);
    private static final int LARGE_TOOL_RESULT_THRESHOLD_CHARS = 200_000;
    private static final int TOOL_RESULT_BATCH_BUDGET_CHARS = 400_000;
    private static final int TOOL_RESULT_PREVIEW_CHARS = 20_000;

    public ApplicationServices(ToolRegistry toolRegistry,
                               PermissionService permissionService,
                               ContextManager contextManager,
                               SessionStore sessionStore,
                               SessionPersistenceRunner sessionPersistenceRunner,
                               AgentLoop agentLoop,
                               ModelAdapter modelAdapter,
                               CompactService compactService,
                               SystemPromptBuilder systemPromptBuilder,
                               WorkspacePathResolver workspacePathResolver,
                               SkillRegistry skillRegistry,
                               McpRuntime mcpRuntime,
                               PermissionStore permissionStore,
                               Path permissionStorePath,
                               Path home,
                               Path cwd,
                               String sessionId) {
        this(toolRegistry, permissionService, contextManager, sessionStore, sessionPersistenceRunner, agentLoop,
                modelAdapter, compactService, systemPromptBuilder, workspacePathResolver, skillRegistry, mcpRuntime,
                permissionStore, permissionStorePath, home, cwd, sessionId, Optional.empty(), Optional.empty());
    }

    /** 保留引入多 Agent 之前的标准构造方法，兼容现有测试和嵌入调用方。 */
    public ApplicationServices(ToolRegistry toolRegistry,
                               PermissionService permissionService,
                               ContextManager contextManager,
                               SessionStore sessionStore,
                               SessionPersistenceRunner sessionPersistenceRunner,
                               AgentLoop agentLoop,
                               ModelAdapter modelAdapter,
                               CompactService compactService,
                               SystemPromptBuilder systemPromptBuilder,
                               WorkspacePathResolver workspacePathResolver,
                               SkillRegistry skillRegistry,
                               McpRuntime mcpRuntime,
                               PermissionStore permissionStore,
                               Path permissionStorePath,
                               Path home,
                               Path cwd,
                               String sessionId,
                               Optional<RuntimeConfig> runtimeConfig) {
        this(toolRegistry, permissionService, contextManager, sessionStore, sessionPersistenceRunner, agentLoop,
                modelAdapter, compactService, systemPromptBuilder, workspacePathResolver, skillRegistry, mcpRuntime,
                permissionStore, permissionStorePath, home, cwd, sessionId, runtimeConfig, Optional.empty());
    }

    public ApplicationServices {
        toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        permissionService = Objects.requireNonNull(permissionService, "permissionService");
        contextManager = Objects.requireNonNull(contextManager, "contextManager");
        sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        sessionPersistenceRunner = Objects.requireNonNull(sessionPersistenceRunner, "sessionPersistenceRunner");
        agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        compactService = Objects.requireNonNull(compactService, "compactService");
        systemPromptBuilder = Objects.requireNonNull(systemPromptBuilder, "systemPromptBuilder");
        workspacePathResolver = Objects.requireNonNull(workspacePathResolver, "workspacePathResolver");
        skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
        mcpRuntime = Objects.requireNonNull(mcpRuntime, "mcpRuntime");
        permissionStore = Objects.requireNonNull(permissionStore, "permissionStore");
        permissionStorePath = Objects.requireNonNull(permissionStorePath, "permissionStorePath");
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        cwd = Objects.requireNonNull(cwd, "cwd");
        if (Objects.requireNonNull(sessionId, "sessionId").isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        subAgentTaskManager = Objects.requireNonNull(subAgentTaskManager, "subAgentTaskManager");
    }

    /**
     * 接收预构建适配器的兼容重载。内置的可分叉适配器会为每个子 Agent 重新绑定；
     * 自定义有状态适配器应使用 {@link #createWithModelAdapterFactory}，以获得完整隔离。
     */
    public static ApplicationServices create(Path home, Path cwd, String sessionId, ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        return createWithAgentTaskEventSink(home, cwd, sessionId, modelAdapter, eventSink,
                defaultAgentTaskEventSink(eventSink), permissionPromptHandler);
    }

    public static ApplicationServices createWithAgentTaskEventSink(
            Path home, Path cwd, String sessionId, ModelAdapter modelAdapter,
            AgentEventSink eventSink, AgentTaskEventSink agentTaskEventSink,
            PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        return createWithModelFactory(home, cwd, sessionId, eventSink, agentTaskEventSink, permissionPromptHandler,
                fixedParentModelFactory(modelAdapter), fixedChildModelFactory(modelAdapter), Optional.empty());
    }

    /**
     * 基于工厂的嵌入入口。父 Agent 注册表和每个子 Agent 注册表都会分别调用一次工厂，
     * 从而保证每个适配器看到的工具结构与其实际可执行工具完全一致。
     */
    public static ApplicationServices createWithModelAdapterFactory(
            Path home, Path cwd, String sessionId, ModelAdapterFactory modelAdapterFactory,
            AgentEventSink eventSink, PermissionPromptHandler permissionPromptHandler) {
        return createWithModelAdapterFactoryAndAgentTaskEventSink(
                home, cwd, sessionId, modelAdapterFactory, eventSink,
                defaultAgentTaskEventSink(eventSink), permissionPromptHandler);
    }

    /** 基于工厂的嵌入入口，并允许显式传入独立的子任务事件接收器。 */
    public static ApplicationServices createWithModelAdapterFactoryAndAgentTaskEventSink(
            Path home, Path cwd, String sessionId, ModelAdapterFactory modelAdapterFactory,
            AgentEventSink eventSink, AgentTaskEventSink agentTaskEventSink,
            PermissionPromptHandler permissionPromptHandler) {
        ModelAdapterFactory actualFactory = Objects.requireNonNull(modelAdapterFactory, "modelAdapterFactory");
        java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> factory =
                (registry, metadata) -> actualFactory.create(registry);
        return createWithModelFactory(home, cwd, sessionId, eventSink, agentTaskEventSink, permissionPromptHandler,
                factory, factory, Optional.empty());
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        return createWithAgentTaskEventSink(home, cwd, sessionId, runtimeConfig, eventSink,
                defaultAgentTaskEventSink(eventSink), permissionPromptHandler);
    }

    public static ApplicationServices createWithAgentTaskEventSink(
            Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
            AgentEventSink eventSink, AgentTaskEventSink agentTaskEventSink,
            PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");

        java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> factory =
                configuredModelFactory(runtimeConfig);
        return createWithModelFactory(home, cwd, sessionId, eventSink, agentTaskEventSink,
                permissionPromptHandler, factory, factory, Optional.of(runtimeConfig));
    }

    /**
     * 供测试或嵌入调用方覆盖已配置父适配器的兼容重载。
     * 内置的可分叉适配器会为每个子 Agent 重新绑定；自定义适配器应优先使用工厂入口。
     */
    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        return createWithAgentTaskEventSink(home, cwd, sessionId, runtimeConfig, modelAdapter, eventSink,
                defaultAgentTaskEventSink(eventSink), permissionPromptHandler);
    }

    public static ApplicationServices createWithAgentTaskEventSink(
            Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig, ModelAdapter modelAdapter,
            AgentEventSink eventSink, AgentTaskEventSink agentTaskEventSink,
            PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        return createWithModelFactory(home, cwd, sessionId, eventSink, agentTaskEventSink, permissionPromptHandler,
                fixedParentModelFactory(modelAdapter), fixedChildModelFactory(modelAdapter),
                Optional.of(runtimeConfig));
    }

    private static ApplicationServices createWithModelFactory(Path home, Path cwd, String sessionId,
                                                              AgentEventSink eventSink,
                                                              AgentTaskEventSink agentTaskEventSink,
                                                              PermissionPromptHandler permissionPromptHandler,
                                                              java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> parentModelFactory,
                                                              java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> childModelFactory,
                                                              Optional<RuntimeConfig> runtimeConfig) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionService permissionService = new PromptingPermissionService(permissionPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        // 注册工具
        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);

        // 这个 config 目前是配置文件，目前配置文件中没有配 mcpserver，mcpruntime 不生效
        // TODO 配置 MCP
        McpRuntime mcpRuntime = runtimeConfig
                .map(config -> McpToolHydrator.hydrate(config.mcpServers(), permissionService, actualCwd))
                .orElseGet(McpRuntime::empty);
        // 注册 mcp 工具
        mcpRuntime.tools().forEach(registry::register);

        // 上下文管理工具
        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        // 向 config 里配置的 url 发送请求，获取 metadata
        Optional<ModelMetadata> modelMetadata = runtimeConfig.flatMap(ApplicationServices::fetchModelMetadata);

        // 子 Agent 使用独立的运行时图。后台模式每次都创建拒绝式权限服务和只读基础工具，
        // 因此工作区外访问只会失败，不会占用主 TUI 的权限弹窗。
        AgentTaskEventSink actualAgentTaskEventSink = Objects.requireNonNull(
                agentTaskEventSink, "agentTaskEventSink");
        ModelAdapterFactory childModelAdapterFactory = childRegistry ->
                Objects.requireNonNull(childModelFactory.apply(childRegistry, modelMetadata), "child modelAdapter");
        ModelContextWindow childContextWindow = runtimeConfig
                .map(config -> modelContextWindow(config, modelMetadata))
                .orElseGet(() -> new ModelContextWindow(128_000, 8_000));
        AgentRuntimeFactory agentRuntimeFactory = new AgentRuntimeFactory(
                mode -> mode == AgentRunMode.BACKGROUND
                        ? createBackgroundReadOnlyToolRegistry()
                        : registry,
                new ChildToolRegistryFactory(),
                childModelAdapterFactory,
                new SubAgentPromptBuilder(),
                () -> new ContextManager(
                        new ToolResultStorage(actualHome.resolve("agent-tool-results")),
                        LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                        TOOL_RESULT_BATCH_BUDGET_CHARS,
                        TOOL_RESULT_PREVIEW_CHARS),
                () -> new ContextStatsCalculator(new TokenAccountingService(), childContextWindow),
                () -> new AutoCompactController(new CompactService(), AutoCompactPolicy.defaults()),
                actualAgentTaskEventSink,
                new AgentRunResultMapper()
        );
        AgentTaskStore agentTaskStore = new AgentTaskStore(actualHome.resolve("agent-tasks"));
        AgentInbox agentInbox = new AgentInbox(agentTaskStore);
        SubAgentTaskManager taskManager = new SubAgentTaskManager(
                agentTaskStore,
                agentInbox,
                (request, cancellationToken) -> agentRuntimeFactory.run(request, cancellationToken),
                actualAgentTaskEventSink
        );
        taskManager.recoverPersistedTasks(actualCwd.toString(), sessionId);
        registry.register(new AgentTool((request, cancellationToken) -> {
            permissionService.beginTurn(request.agentId());
            try {
                return agentRuntimeFactory.run(request, cancellationToken);
            } finally {
                permissionService.endTurn(request.agentId());
            }
        }, taskManager));
        registry.register(new TaskListTool(taskManager));
        registry.register(new TaskStatusTool(taskManager));
        registry.register(new TaskOutputTool(taskManager));
        registry.register(new TaskCancelTool(taskManager));

        // 父 Adapter 必须在多 Agent 工具注册完成后创建，保证 provider schema 包含公开工具。
        ModelAdapter modelAdapter;
        try {
            modelAdapter = Objects.requireNonNull(parentModelFactory.apply(registry, modelMetadata), "modelAdapter");
        } catch (RuntimeException exception) {
            taskManager.close();
            mcpRuntime.close();
            throw exception;
        }

        // 创建会话存储服务
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        // 持久化服务
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );

        // 上下文压缩服务
        CompactService compactService = new CompactService();
        AgentInboxTurnMessageSource turnMessageSource = new AgentInboxTurnMessageSource(agentInbox, actualCwd);
        AgentLoop agentLoop = runtimeConfig
                .map(config -> new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                        new ContextStatsCalculator(new TokenAccountingService(), modelContextWindow(config, modelMetadata)), // 上下文统计器
                        new AutoCompactController(compactService, AutoCompactPolicy.defaults()), turnMessageSource)) // 自动压缩控制器，采用默认策略
                .orElseGet(() -> new AgentLoop(modelAdapter, eventSink, registry, contextManager, turnMessageSource)); // 如果没有 runtimeconfig，就没上面两个
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, mcpRuntime, permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, runtimeConfig, Optional.of(taskManager));
    }

    private static java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter>
    fixedParentModelFactory(ModelAdapter modelAdapter) {
        ModelAdapter actualAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        return (registry, metadata) -> actualAdapter instanceof ForkableModelAdapter forkable
                ? forkable.fork(registry)
                : actualAdapter;
    }

    private static java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter>
    fixedChildModelFactory(ModelAdapter modelAdapter) {
        ModelAdapter actualAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        return (registry, metadata) -> actualAdapter instanceof ForkableModelAdapter forkable
                ? forkable.fork(registry)
                : messages -> actualAdapter.next(messages);
    }

    private static java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter>
    configuredModelFactory(RuntimeConfig runtimeConfig) {
        RuntimeConfig actualConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        return (registry, metadata) -> {
            if (actualConfig.provider() == ProviderKind.MOCK) {
                return new MockModelAdapter("mock final");
            }
            if (actualConfig.provider() == ProviderKind.OPENAI_COMPATIBLE) {
                return new OpenAIModelAdapter(actualConfig, registry);
            }
            return new AnthropicModelAdapter(actualConfig, registry,
                    new HttpAnthropicTransport(java.net.http.HttpClient.newHttpClient(),
                            actualConfig.providerTimeout()),
                    Optional.of(resolveModelContextProfile(actualConfig, metadata).resolvedMaxOutputTokens()));
        };
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig) {
        return modelContextWindow(runtimeConfig, Optional.empty());
    }

    private static AgentTaskEventSink defaultAgentTaskEventSink(AgentEventSink eventSink) {
        AgentEventSink actualEventSink = Objects.requireNonNull(eventSink, "eventSink");
        return actualEventSink instanceof AgentTaskEventSink taskSink
                ? taskSink
                : AgentTaskEventSink.noOp();
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig, Optional<ModelMetadata> metadata) {
        ModelContextProfile profile = resolveModelContextProfile(runtimeConfig, metadata);
        return new ModelContextWindow(profile.contextWindow(), profile.outputReserve());
    }

    private static ModelContextProfile resolveModelContextProfile(RuntimeConfig runtimeConfig,
                                                                  Optional<ModelMetadata> metadata) {
        return new ModelMetadataResolver().resolve(
                runtimeConfig.model(),
                runtimeConfig.contextWindow(),
                runtimeConfig.maxOutputTokens(),
                metadata
        );
    }

    private static Optional<ModelMetadata> fetchModelMetadata(RuntimeConfig runtimeConfig) {
        if (!shouldFetchModelMetadata(runtimeConfig)) {
            return Optional.empty();
        }
        return metadataClient(runtimeConfig).fetch(runtimeConfig.model());
    }

    private static boolean shouldFetchModelMetadata(RuntimeConfig runtimeConfig) {
        return runtimeConfig.provider() != ProviderKind.MOCK;
    }

    private static AnthropicModelsApiClient metadataClient(RuntimeConfig runtimeConfig) {
        HttpAnthropicTransport transport = new HttpAnthropicTransport(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(MODEL_METADATA_TIMEOUT)
                        .build(),
                MODEL_METADATA_TIMEOUT
        );
        return new AnthropicModelsApiClient(runtimeConfig, transport);
    }

    private static ToolRegistry createBuiltInToolRegistry(PermissionService permissionService,
                                                         WorkspacePathResolver workspacePathResolver,
                                                         SkillRegistry skillRegistry) {
        ToolRegistry registry = new ToolRegistry();
        // 维护一个 map 保存，name -> tool
        registry.register(new AskUserTool());
        registry.register(new LoadSkillTool(skillRegistry));
        registry.register(new ReadFileTool(ReadFilePathAccess.fromPermissionService(permissionService),
                workspacePathResolver));
        registry.register(new RunCommandTool(permissionService, java.time.Duration.ofSeconds(5),
                workspacePathResolver));
        registry.register(new ListFilesTool(permissionService, workspacePathResolver));
        registry.register(new GrepFilesTool(permissionService, workspacePathResolver));
        registry.register(new WriteFileTool(permissionService, workspacePathResolver));
        registry.register(new EditFileTool(permissionService, workspacePathResolver));
        registry.register(new PatchFileTool(permissionService, workspacePathResolver));
        registry.register(new ModifyFileTool(permissionService, workspacePathResolver));
        return registry;
    }

    private static ToolRegistry createBackgroundReadOnlyToolRegistry() {
        PermissionService denyWithoutPrompt = new PromptingPermissionService(
                PermissionPromptHandler.unavailable(), PermissionStore.none());
        WorkspacePathResolver resolver = new WorkspacePathResolver();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(ReadFilePathAccess.fromPermissionService(denyWithoutPrompt), resolver));
        registry.register(new ListFilesTool(denyWithoutPrompt, resolver));
        registry.register(new GrepFilesTool(denyWithoutPrompt, resolver));
        return registry;
    }

    /**
     * 为一次新的 Agent turn 构造请求对象。
     *
     * <p>调用方传入当前会话消息和最大 step 数后，这里会生成新的 turn id，
     * 绑定当前 {@code cwd} 与 {@code sessionId}，并在消息列表前刷新最新的系统提示词。
     * 返回的 {@link AgentTurnRequest} 会被继续交给 {@link #runTurn(AgentTurnRequest)} 执行。</p>
     *
     * @param messages 本轮要发送给模型的会话消息，通常包含历史消息和当前用户输入
     * @param maxSteps 本轮最多允许执行的模型/工具 step 数量
     * @return 带有新 turn id、当前会话信息和最新 system prompt 的 turn 请求
     */
    public AgentTurnRequest turnRequest(List<ChatMessage> messages, int maxSteps) {
        return new AgentTurnRequest(
                UUID.randomUUID().toString(),
                cwd,
                sessionId,
                // 每次进入模型前都刷新 system prompt，避免工具、技能或 MCP 信息过期。
                withFreshSystemPrompt(messages),
                maxSteps,
                Optional.empty()
        );
    }

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        AgentTurnRequest actualRequest = Objects.requireNonNull(request, "request");
        // 刷新 prompt，保证 message 里是最新的 prompt
        actualRequest = new AgentTurnRequest(
                actualRequest.turnId(),
                actualRequest.cwd(),
                actualRequest.sessionId(),
                withFreshSystemPrompt(actualRequest.messages()),
                actualRequest.maxSteps(),
                actualRequest.modelName(),
                actualRequest.cancellationToken()
        );
        // 开启一轮 turn 的临时权限作用域，内部维护一个 map，key 为 turnid，value 为 set（保存授权集合）
        permissionService.beginTurn(actualRequest.turnId());
        try {
            return agentLoop.runTurn(actualRequest);
        } finally {
            // 清除当前 turn 的权限作用域
            permissionService.endTurn(actualRequest.turnId());
        }
    }

    public List<ChatMessage> sessionMessages() {
        return sessionStore.loadMessagesSinceLatestCompactBoundary(sessionId, cwd.toString());
    }

    /**
     * 重新读取当前工作区的分层记忆，供本地命令展示或下一轮 Prompt 构建使用。
     *
     * @return 当前时刻的最新记忆快照
     */
    public MemorySnapshot memorySnapshot() {
        return systemPromptBuilder.loadMemory(home, cwd);
    }

    /**
     * 检测当前 Java 项目结构并幂等生成 MiniCode 项目记忆文件。
     *
     * @return 可直接展示给用户的初始化报告
     */
    public String initializeProject() {
        return ProjectInitializer.renderReport(new ProjectInitializer().initialize(cwd));
    }

    public ManualCompactResult manualCompact() {
        ManualCompactResult result = compactService.compact(new CompactRequest(
                withFreshSystemPrompt(sessionMessages()), // 加载从上次压缩后的消息+系统提示词
                modelAdapter,
                CompactTrigger.MANUAL
        ));
        if (result.status() == CompactStatus.COMPACTED) {
            List<PersistenceAction> actions = new ArrayList<>();
            actions.add(new PersistenceAction.AppendCompactBoundaryAction(
                    result.boundary().orElseThrow().summaryMessage(),
                    result.boundary().orElseThrow().metadata()
            ));
            List<ChatMessage> retainedMessages = retainedMessagesAfterCompactBoundary(result);
            if (!retainedMessages.isEmpty()) {
                actions.add(new PersistenceAction.AppendMessagesAction(retainedMessages));
            }
            sessionPersistenceRunner.apply(new TurnPersistencePlan(actions));
        }
        return result;
    }

    public List<McpServerSummary> mcpServerSummaries() {
        return mcpRuntime.summaries();
    }

    public void close() {
        try {
            subAgentTaskManager.ifPresent(SubAgentTaskManager::close);
        } finally {
            mcpRuntime.close();
        }
    }

    private List<ChatMessage> retainedMessagesAfterCompactBoundary(ManualCompactResult result) {
        ChatMessage summary = result.boundary().orElseThrow().summaryMessage();
        boolean skippedSummary = false;
        List<ChatMessage> retained = new ArrayList<>();
        for (ChatMessage message : result.messages()) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (!skippedSummary && message.equals(summary)) {
                skippedSummary = true;
                continue;
            }
            retained.add(message);
        }
        return List.copyOf(retained);
    }

    /**
     * 为一次新的模型请求刷新系统提示词（systemMessage）。
     *
     * <p>系统提示词依赖当前运行环境，例如工作目录、已注册工具、已发现技能和 MCP 服务。
     * 因此每次进入模型前都重新生成最新的 {@link SystemMessage}，并过滤掉历史消息中旧的
     * system message，避免把过期的环境信息继续喂给模型。</p>
     *
     * @param messages 原始会话消息，可能包含历史 system message
     * @return 以最新 system message 开头、且不包含旧 system message 的消息列表
     */
    private List<ChatMessage> withFreshSystemPrompt(List<ChatMessage> messages) {
        List<ChatMessage> refreshed = new ArrayList<>();

        // 先根据当前应用服务状态生成最新 system prompt，确保模型看到的是当前工具/技能/MCP 信息。
        refreshed.add(new SystemMessage(systemPromptBuilder.build(
                new SystemPromptBuilder.Input(home, cwd, toolRegistry, skillRegistry.summaries(),
                        mcpRuntime.summaries())
        )));

        // 再追加历史中的普通对话消息；旧 SystemMessage 会被跳过，避免重复或过期。
        for (ChatMessage message : messages) {
            if (!(message instanceof SystemMessage)) {
                refreshed.add(message);
            }
        }

        // 返回不可变副本，避免调用方继续修改这次模型请求的消息序列。
        return List.copyOf(refreshed);
    }
}
