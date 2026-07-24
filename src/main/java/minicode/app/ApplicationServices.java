package minicode.app;

import minicode.agent.event.AgentTaskEventSink;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.runtime.AgentRunResultMapper;
import minicode.agent.runtime.AgentRuntimeFactory;
import minicode.agent.runtime.ChildToolRegistryFactory;
import minicode.agent.runtime.ModelAdapterFactory;
import minicode.agent.task.AgentTaskExecutor;
import minicode.agent.task.SubAgentTaskManager;
import minicode.agent.task.SubAgentTurnMessageSource;
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
import minicode.core.step.AgentStep;
import minicode.core.turn.CancellationToken;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.loop.ModelAdapter;
import minicode.model.MockModelAdapter;
import minicode.model.ModelContextProfile;
import minicode.model.ModelMetadata;
import minicode.model.ModelMetadataResolver;
import minicode.model.anthropic.AnthropicModelsApiClient;
import minicode.model.anthropic.HttpAnthropicTransport;
import minicode.model.langchain4j.LangChain4jModelAdapter;
import minicode.init.ProjectInitializer;
import minicode.mcp.McpRuntime;
import minicode.mcp.McpServerSummary;
import minicode.memory.MemorySnapshot;
import minicode.mcp.McpToolHydrator;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.model.PermissionPromptResult;
import minicode.permissions.model.PermissionRequest;
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
import java.util.function.Supplier;

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
        RuntimeModelAdapterFactory factory = new ForwardingRuntimeModelAdapterFactory(actualFactory);
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

        RuntimeModelAdapterFactory factory = configuredModelFactory(runtimeConfig);
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
                                                              RuntimeModelAdapterFactory parentModelFactory,
                                                              RuntimeModelAdapterFactory childModelFactory,
                                                              Optional<RuntimeConfig> runtimeConfig) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionPromptHandler actualPromptHandler = Objects.requireNonNull(
                permissionPromptHandler, "permissionPromptHandler");
        PermissionPromptHandler serializedPromptHandler =
                new SerializedPermissionPromptHandler(actualPromptHandler);
        PermissionService permissionService = new PromptingPermissionService(
                serializedPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        // 注册工具
        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);

        // 这个 config 目前是配置文件，目前配置文件中没有配 mcpserver，mcpruntime 不生效
        // TODO 配置 MCP
        McpRuntime mcpRuntime = runtimeConfig.isPresent()
                ? McpToolHydrator.hydrate(runtimeConfig.orElseThrow().mcpServers(), permissionService, actualCwd)
                : McpRuntime.empty();
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

        // 子 Agent 使用独立上下文和经过角色过滤的 Registry；同步和后台路径共享父权限链。
        AgentTaskEventSink actualAgentTaskEventSink = Objects.requireNonNull(
                agentTaskEventSink, "agentTaskEventSink");

        // 为子 Agent 绑定已经解析出的模型元数据，避免运行时再依赖两参数通用函数。
        ModelAdapterFactory childModelAdapterFactory =
                new BoundChildModelAdapterFactory(childModelFactory, modelMetadata);

        ModelContextWindow childContextWindow = runtimeConfig.isPresent()
                ? modelContextWindow(runtimeConfig.orElseThrow(), modelMetadata)
                : new ModelContextWindow(128_000, 8_000);

        // 负责生产子 agent 运行环境
        AgentRuntimeFactory agentRuntimeFactory = new AgentRuntimeFactory(
                registry,
                new ChildToolRegistryFactory(),
                childModelAdapterFactory,
                new SubAgentPromptBuilder(),
                new ChildContextManagerFactory(actualHome.resolve("agent-tool-results")),
                new ChildContextStatsFactory(childContextWindow),
                new ChildAutoCompactControllerFactory(),
                actualAgentTaskEventSink,
                new AgentRunResultMapper()
        );
        // 包装子 agent 执行过程，建立子 agent 自己的权限 turn
        AgentTaskExecutor childExecutor =
                new PermissionScopedAgentTaskExecutor(permissionService, agentRuntimeFactory);
        // 创建后台任务管理器。
        SubAgentTaskManager taskManager = new SubAgentTaskManager(childExecutor);

        // 把agentTool 注册进父 Agent 工具表
        registry.register(new AgentTool(childExecutor, taskManager));

        // 父 Adapter 必须在多 Agent 工具注册完成后创建，保证 provider schema 包含公开工具。
        ModelAdapter modelAdapter;
        try {
            modelAdapter = Objects.requireNonNull(parentModelFactory.create(registry, modelMetadata), "modelAdapter");
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
                        ApplicationServices::newUuid,
                        lastEventUuid)
        );

        // 上下文压缩服务
        CompactService compactService = new CompactService();
        SubAgentTurnMessageSource turnMessageSource = new SubAgentTurnMessageSource(taskManager);
        AgentLoop agentLoop;
        if (runtimeConfig.isPresent()) {
            ModelContextWindow parentContextWindow = modelContextWindow(runtimeConfig.orElseThrow(), modelMetadata);
            agentLoop = new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                    new ContextStatsCalculator(new TokenAccountingService(), parentContextWindow),
                    new AutoCompactController(compactService, AutoCompactPolicy.defaults()), turnMessageSource);
        } else {
            agentLoop = new AgentLoop(modelAdapter, eventSink, registry, contextManager, turnMessageSource);
        }
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, mcpRuntime, permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, runtimeConfig, Optional.of(taskManager));
    }

    private static RuntimeModelAdapterFactory
    fixedParentModelFactory(ModelAdapter modelAdapter) {
        return new FixedParentModelAdapterFactory(modelAdapter);
    }

    private static RuntimeModelAdapterFactory
    fixedChildModelFactory(ModelAdapter modelAdapter) {
        return new FixedChildModelAdapterFactory(modelAdapter);
    }

    private static RuntimeModelAdapterFactory
    configuredModelFactory(RuntimeConfig runtimeConfig) {
        return new ConfiguredModelAdapterFactory(runtimeConfig);
    }

    private static String newUuid() {
        return UUID.randomUUID().toString();
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

    /** 应用装配阶段使用的模型工厂，同时接收已经解析出的模型元数据。 */
    private interface RuntimeModelAdapterFactory {
        ModelAdapter create(ToolRegistry toolRegistry, Optional<ModelMetadata> metadata);
    }

    /** 将公开的单参数工厂接入应用装配流程。 */
    private static final class ForwardingRuntimeModelAdapterFactory implements RuntimeModelAdapterFactory {
        private final ModelAdapterFactory delegate;

        private ForwardingRuntimeModelAdapterFactory(ModelAdapterFactory delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ModelAdapter create(ToolRegistry toolRegistry, Optional<ModelMetadata> metadata) {
            Objects.requireNonNull(metadata, "metadata");
            return delegate.create(Objects.requireNonNull(toolRegistry, "toolRegistry"));
        }
    }

    /** 为子 Agent 固定模型元数据，使运行时只需传入自己的工具注册表。 */
    private static final class BoundChildModelAdapterFactory implements ModelAdapterFactory {
        private final RuntimeModelAdapterFactory delegate;
        private final Optional<ModelMetadata> metadata;

        private BoundChildModelAdapterFactory(RuntimeModelAdapterFactory delegate,
                                              Optional<ModelMetadata> metadata) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.metadata = Objects.requireNonNull(metadata, "metadata");
        }

        @Override
        public ModelAdapter create(ToolRegistry childToolRegistry) {
            return Objects.requireNonNull(
                    delegate.create(childToolRegistry, metadata),
                    "child modelAdapter");
        }
    }

    /** 为预构建且可分叉的父适配器重新绑定完整父工具表。 */
    private static final class FixedParentModelAdapterFactory implements RuntimeModelAdapterFactory {
        private final ModelAdapter modelAdapter;

        private FixedParentModelAdapterFactory(ModelAdapter modelAdapter) {
            this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        }

        @Override
        public ModelAdapter create(ToolRegistry toolRegistry, Optional<ModelMetadata> metadata) {
            Objects.requireNonNull(metadata, "metadata");
            if (modelAdapter instanceof ForkableModelAdapter forkable) {
                return forkable.fork(toolRegistry);
            }
            return modelAdapter;
        }
    }

    /** 为每个子 Agent 返回独立适配器对象，并在支持时重新绑定子工具表。 */
    private static final class FixedChildModelAdapterFactory implements RuntimeModelAdapterFactory {
        private final ModelAdapter modelAdapter;

        private FixedChildModelAdapterFactory(ModelAdapter modelAdapter) {
            this.modelAdapter = Objects.requireNonNull(modelAdapter, "modelAdapter");
        }

        @Override
        public ModelAdapter create(ToolRegistry toolRegistry, Optional<ModelMetadata> metadata) {
            Objects.requireNonNull(metadata, "metadata");
            if (modelAdapter instanceof ForkableModelAdapter forkable) {
                return forkable.fork(toolRegistry);
            }
            return new IsolatedDelegatingModelAdapter(modelAdapter);
        }
    }

    /** 根据运行配置选择并创建具体 Provider 适配器。 */
    private static final class ConfiguredModelAdapterFactory implements RuntimeModelAdapterFactory {
        private final RuntimeConfig runtimeConfig;

        private ConfiguredModelAdapterFactory(RuntimeConfig runtimeConfig) {
            this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        }

        @Override
        public ModelAdapter create(ToolRegistry toolRegistry, Optional<ModelMetadata> metadata) {
            ToolRegistry actualTools = Objects.requireNonNull(toolRegistry, "toolRegistry");
            Optional<ModelMetadata> actualMetadata = Objects.requireNonNull(metadata, "metadata");
            if (runtimeConfig.provider() == ProviderKind.MOCK) {
                return new MockModelAdapter("mock final");
            }

            // 生产 Provider 统一走 LangChain4j 低层 ChatModel；这里只替换模型适配层，
            // 解析出的模型输出上限和当前 Agent 的 ToolRegistry 仍由原 Runtime 注入。
            return new LangChain4jModelAdapter(runtimeConfig, actualTools,
                    Optional.of(resolveModelContextProfile(runtimeConfig, actualMetadata)
                            .resolvedMaxOutputTokens()));
        }
    }

    /** 为不可分叉的调用方适配器提供独立的无状态转发对象。 */
    private static final class IsolatedDelegatingModelAdapter implements ModelAdapter {
        private final ModelAdapter delegate;

        private IsolatedDelegatingModelAdapter(ModelAdapter delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            return delegate.next(messages);
        }
    }

    /** 串行化父 Agent 与后台子 Agent 的权限交互，避免终端输入竞争。 */
    private static final class SerializedPermissionPromptHandler implements PermissionPromptHandler {
        private final PermissionPromptHandler delegate;
        private final Object promptLock = new Object();

        private SerializedPermissionPromptHandler(PermissionPromptHandler delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public PermissionPromptResult prompt(PermissionRequest request) {
            synchronized (promptLock) {
                return delegate.prompt(request);
            }
        }
    }

    /** 在独立权限 Turn 中执行一次子 Agent 请求。 */
    private static final class PermissionScopedAgentTaskExecutor implements AgentTaskExecutor {
        private final PermissionService permissionService;
        private final AgentRuntimeFactory runtimeFactory;

        private PermissionScopedAgentTaskExecutor(PermissionService permissionService,
                                                  AgentRuntimeFactory runtimeFactory) {
            this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
            this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        }

        @Override
        public AgentRunResult execute(AgentTaskRequest request,
                                      CancellationToken cancellationToken) {
            permissionService.beginTurn(request.agentId());
            try {
                return runtimeFactory.run(request, cancellationToken);
            } finally {
                permissionService.endTurn(request.agentId());
            }
        }
    }

    /** 每次子 Agent 运行创建独立的工具结果存储与上下文管理器。 */
    private static final class ChildContextManagerFactory implements Supplier<ContextManager> {
        private final Path toolResultStoragePath;

        private ChildContextManagerFactory(Path toolResultStoragePath) {
            this.toolResultStoragePath = Objects.requireNonNull(toolResultStoragePath, "toolResultStoragePath");
        }

        @Override
        public ContextManager get() {
            return new ContextManager(
                    new ToolResultStorage(toolResultStoragePath),
                    LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                    TOOL_RESULT_BATCH_BUDGET_CHARS,
                    TOOL_RESULT_PREVIEW_CHARS);
        }
    }

    /** 每次子 Agent 运行创建独立的上下文统计器。 */
    private static final class ChildContextStatsFactory implements Supplier<ContextStatsCalculator> {
        private final ModelContextWindow contextWindow;

        private ChildContextStatsFactory(ModelContextWindow contextWindow) {
            this.contextWindow = Objects.requireNonNull(contextWindow, "contextWindow");
        }

        @Override
        public ContextStatsCalculator get() {
            return new ContextStatsCalculator(new TokenAccountingService(), contextWindow);
        }
    }

    /** 每次子 Agent 运行创建独立的自动压缩控制器。 */
    private static final class ChildAutoCompactControllerFactory implements Supplier<AutoCompactController> {
        @Override
        public AutoCompactController get() {
            return new AutoCompactController(new CompactService(), AutoCompactPolicy.defaults());
        }
    }
}
