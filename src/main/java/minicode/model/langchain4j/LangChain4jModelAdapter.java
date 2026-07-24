package minicode.model.langchain4j;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import minicode.config.RuntimeConfig;
import minicode.core.loop.ForkableModelAdapter;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;
import minicode.core.step.AgentStep;
import minicode.model.ModelLimits;
import minicode.model.ProviderRequestException;
import minicode.tools.api.Tool;
import minicode.tools.registry.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 LangChain4j 的模型 Provider 适配器。
 *
 * <p>这个类只负责把 CodeAgent 的消息和工具描述转换成 Provider 请求，再把模型响应转换成
 * {@link AgentStep}。工具执行、权限、持久化、上下文压缩、取消和子 Agent 编排仍然只由
 * CodeAgent 原有的 AgentLoop 与 ToolRegistry 负责。</p>
 */
public final class LangChain4jModelAdapter implements ForkableModelAdapter {
    private final RuntimeConfig runtimeConfig;
    private final ToolRegistry tools;
    private final int maxOutputTokens;
    private final ModelFactory modelFactory;
    private final MessageMapper messageMapper;
    private final ToolSpecificationMapper toolMapper;
    private final ResponseMapper responseMapper;
    private final ProviderExceptionMapper exceptionMapper;
    private final ProviderRequestContext requestContext;
    private final ChatModel chatModel;

    /**
     * 使用现有运行配置、工具注册表和已经解析出的模型输出上限创建适配器。
     *
     * @param runtimeConfig CodeAgent 原有的 Provider 配置
     * @param tools 当前 Agent 实际可见、可执行的工具注册表
     * @param resolvedMaxOutputTokens 模型元数据解析出的输出上限；为空时回退到本地模型规则
     */
    public LangChain4jModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools,
                                   Optional<Integer> resolvedMaxOutputTokens) {
        this(runtimeConfig, tools, resolveMaxOutputTokens(runtimeConfig, resolvedMaxOutputTokens),
                new ChatModelFactory()::create,
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());
    }

    LangChain4jModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools, int maxOutputTokens,
                            ModelFactory modelFactory,
                            MessageMapper messageMapper,
                            ToolSpecificationMapper toolMapper,
                            ResponseMapper responseMapper,
                            ProviderExceptionMapper exceptionMapper) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.tools = Objects.requireNonNull(tools, "tools");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.modelFactory = Objects.requireNonNull(modelFactory, "modelFactory");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.toolMapper = Objects.requireNonNull(toolMapper, "toolMapper");
        this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper");
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper, "exceptionMapper");

        // 每个 Adapter 都持有独立的请求上下文和 ChatModel。
        // fork 子 Agent 时重新执行这里，避免父子 Agent 共享 ThreadLocal 或工具视图。
        this.requestContext = new ProviderRequestContext();
        this.chatModel = Objects.requireNonNull(
                modelFactory.create(runtimeConfig, maxOutputTokens, requestContext),
                "chatModel");
    }

    /**
     * 完成一次“CodeAgent 消息 -> LangChain4j 请求 -> CodeAgent Step”的同步转换。
     *
     * @param messages 当前会话的完整消息快照
     * @return 只描述模型下一步意图的 {@link AgentStep}，不会在这里执行工具
     */
    @Override
    public AgentStep next(List<ChatMessage> messages) {
        List<ChatMessage> actualMessages = List.copyOf(Objects.requireNonNull(messages, "messages"));

        // 工具可能在 MCP 刷新或子 Agent 过滤后发生变化，因此每次请求都从 Registry 重新读取，
        // 不能把构造 Adapter 时的工具列表永久缓存下来。
        List<Tool> currentTools = tools.list();
        try {
            // 第一层转换：只处理 Provider 可理解的消息和请求级工具声明。
            List<dev.langchain4j.data.message.ChatMessage> providerMessages =
                    messageMapper.map(actualMessages);
            List<ToolSpecification> toolSpecifications = toolMapper.map(currentTools);
            ChatRequest request = ChatRequest.builder()
                    .messages(providerMessages)
                    .toolSpecifications(toolSpecifications)
                    .build();

            // LangChain4j 的通用模型无法完整表达原始工具 Schema 和 Anthropic thinking block。
            // 这里先保存无损快照，HTTP 兼容层会在真正发请求前按名称/顺序把原始数据写回。
            ProviderRequestContext.Snapshot snapshot =
                    ProviderRequestContext.snapshot(runtimeConfig.provider(), currentTools, actualMessages);
            ChatResponse response = requestContext.within(snapshot, () -> chatModel.chat(request));

            // 第二层转换：只生成 AgentStep；是否执行工具仍由 AgentLoop 决定。
            return responseMapper.map(runtimeConfig.provider(), response);
        } catch (ProviderRequestException exception) {
            // 映射器已经生成 CodeAgent 标准异常时直接透传，避免重复包装丢失状态码和 retryable。
            throw exception;
        } catch (RuntimeException exception) {
            // 其余 LangChain4j/HTTP 异常统一收敛为 Runtime 已认识的 ProviderRequestException。
            throw exceptionMapper.map(exception);
        }
    }

    /**
     * 为子 Agent 创建绑定其过滤后工具注册表的独立适配器。
     *
     * @param toolRegistry 子 Agent 实际可执行的工具注册表
     * @return 不与父 Agent 共享 ChatModel 和调用上下文的新适配器
     */
    @Override
    public ModelAdapter fork(ToolRegistry toolRegistry) {
        // Mapper 都是无状态对象，可以复用；构造函数仍会新建 requestContext 与 ChatModel。
        return new LangChain4jModelAdapter(runtimeConfig,
                Objects.requireNonNull(toolRegistry, "toolRegistry"),
                maxOutputTokens,
                modelFactory,
                messageMapper,
                toolMapper,
                responseMapper,
                exceptionMapper);
    }

    private static int resolveMaxOutputTokens(RuntimeConfig config,
                                              Optional<Integer> resolvedMaxOutputTokens) {
        RuntimeConfig actualConfig = Objects.requireNonNull(config, "runtimeConfig");

        // ApplicationServices 已经通过模型元数据解析出上限时优先使用；
        // 独立构造或测试路径没有元数据时，再沿用 CodeAgent 原有的 ModelLimits 规则。
        return Objects.requireNonNull(resolvedMaxOutputTokens, "resolvedMaxOutputTokens")
                .filter(value -> value > 0)
                .orElseGet(() -> ModelLimits.resolveMaxOutputTokens(
                        actualConfig.model(), actualConfig.maxOutputTokens()));
    }

    @FunctionalInterface
    interface ModelFactory {
        ChatModel create(RuntimeConfig config, int maxOutputTokens,
                         ProviderRequestContext requestContext);
    }
}
