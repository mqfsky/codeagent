package minicode.app;

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
import minicode.mcp.McpRuntime;
import minicode.mcp.McpServerSummary;
import minicode.mcp.McpToolHydrator;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.api.PermissionService;
import minicode.permissions.service.PromptingPermissionService;
import minicode.permissions.store.JsonPermissionStore;
import minicode.permissions.store.PermissionStore;
import minicode.prompt.SystemPromptBuilder;
import minicode.session.factory.SessionEventFactory;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.store.SessionStore;
import minicode.skills.SkillDiscovery;
import minicode.skills.SkillRegistry;
import minicode.tools.builtin.AskUserTool;
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

/**
 * 应用运行期的核心服务集合。
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
                                  Optional<RuntimeConfig> runtimeConfig) {
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
                permissionStore, permissionStorePath, home, cwd, sessionId, Optional.empty());
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
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Path actualHome = Objects.requireNonNull(home, "home");
        Path actualCwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
        Path permissionStorePath = actualHome.resolve("permissions.json");
        PermissionStore permissionStore = new JsonPermissionStore(permissionStorePath);
        PermissionService permissionService = new PromptingPermissionService(permissionPromptHandler, permissionStore);
        WorkspacePathResolver workspacePathResolver = new WorkspacePathResolver();
        SkillRegistry skillRegistry = new SkillRegistry(new SkillDiscovery(actualHome, actualCwd).discover());

        // 注册工具
        ToolRegistry registry = createBuiltInToolRegistry(permissionService, workspacePathResolver, skillRegistry);

        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );
        CompactService compactService = new CompactService();
        AgentLoop agentLoop = new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(128_000, 8_000)),
                new AutoCompactController(compactService, AutoCompactPolicy.defaults()));
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, McpRuntime.empty(), permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, Optional.empty());
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        return createWithModelFactory(home, cwd, sessionId, eventSink, permissionPromptHandler, (registry, metadata) -> {
            if (runtimeConfig.provider() == ProviderKind.MOCK) {
                return new MockModelAdapter("mock final");
            }
            if (runtimeConfig.provider() == ProviderKind.OPENAI_COMPATIBLE) {
                return new OpenAIModelAdapter(runtimeConfig, registry);
            }
            return new AnthropicModelAdapter(runtimeConfig, registry,
                    new HttpAnthropicTransport(java.net.http.HttpClient.newHttpClient(),
                            runtimeConfig.providerTimeout()),
                    Optional.of(resolveModelContextProfile(runtimeConfig, metadata).resolvedMaxOutputTokens()));
        }, Optional.of(runtimeConfig));
    }

    public static ApplicationServices create(Path home, Path cwd, String sessionId, RuntimeConfig runtimeConfig,
                                             ModelAdapter modelAdapter,
                                             AgentEventSink eventSink,
                                             PermissionPromptHandler permissionPromptHandler) {
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        Objects.requireNonNull(modelAdapter, "modelAdapter");
        return createWithModelFactory(home, cwd, sessionId, eventSink, permissionPromptHandler,
                (ignored, metadata) -> modelAdapter, Optional.of(runtimeConfig));
    }

    private static ApplicationServices createWithModelFactory(Path home, Path cwd, String sessionId,
                                                              AgentEventSink eventSink,
                                                              PermissionPromptHandler permissionPromptHandler,
                                                              java.util.function.BiFunction<ToolRegistry, Optional<ModelMetadata>, ModelAdapter> modelFactory,
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
        McpRuntime mcpRuntime = runtimeConfig
                .map(config -> McpToolHydrator.hydrate(config.mcpServers(), permissionService, actualCwd))
                .orElseGet(McpRuntime::empty);
        mcpRuntime.tools().forEach(registry::register);
        ContextManager contextManager = new ContextManager(
                new ToolResultStorage(actualHome.resolve("tool-results")),
                LARGE_TOOL_RESULT_THRESHOLD_CHARS,
                TOOL_RESULT_BATCH_BUDGET_CHARS,
                TOOL_RESULT_PREVIEW_CHARS
        );
        Optional<ModelMetadata> modelMetadata = runtimeConfig.flatMap(ApplicationServices::fetchModelMetadata);
        ModelAdapter modelAdapter = Objects.requireNonNull(modelFactory.apply(registry, modelMetadata), "modelAdapter");
        SessionStore sessionStore = new SessionStore(actualHome.resolve("sessions"));
        Optional<String> lastEventUuid = sessionStore.latestEventUuid(sessionId, actualCwd.toString());
        SessionPersistenceRunner persistenceRunner = new SessionPersistenceRunner(
                sessionStore,
                new SessionEventFactory(sessionId, actualCwd.toString(),
                        java.time.Clock.systemUTC(),
                        () -> UUID.randomUUID().toString(),
                        lastEventUuid)
        );
        CompactService compactService = new CompactService();
        AgentLoop agentLoop = runtimeConfig
                .map(config -> new AgentLoop(modelAdapter, eventSink, registry, contextManager,
                        new ContextStatsCalculator(new TokenAccountingService(), modelContextWindow(config, modelMetadata)),
                        new AutoCompactController(compactService, AutoCompactPolicy.defaults())))
                .orElseGet(() -> new AgentLoop(modelAdapter, eventSink, registry, contextManager));
        return new ApplicationServices(registry, permissionService, contextManager, sessionStore,
                persistenceRunner, agentLoop, modelAdapter, compactService, new SystemPromptBuilder(), workspacePathResolver,
                skillRegistry, mcpRuntime, permissionStore, permissionStorePath, actualHome,
                actualCwd, sessionId, runtimeConfig);
    }

    private static ModelContextWindow modelContextWindow(RuntimeConfig runtimeConfig) {
        return modelContextWindow(runtimeConfig, Optional.empty());
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
        // 开启一轮 turn 的临时权限作用域。
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

    public ManualCompactResult manualCompact() {
        ManualCompactResult result = compactService.compact(new CompactRequest(
                withFreshSystemPrompt(sessionMessages()),
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
        mcpRuntime.close();
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
