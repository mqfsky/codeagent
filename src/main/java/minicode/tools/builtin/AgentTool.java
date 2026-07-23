package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentType;
import minicode.agent.runtime.AgentRuntimeFactory;
import minicode.agent.task.AgentTaskExecutor;
import minicode.agent.task.SubAgentTaskManager;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import minicode.tools.validation.ToolInputValidation;
import minicode.tools.validation.ValidatedInputBuilder;

import java.util.Objects;
import java.util.Set;

/**
 * 向隔离的内置子 Agent 委派一项聚焦任务的工具。
 *
 * <p>父 Agent 通过该工具指定子 Agent 角色、任务描述和完整提示词。
 * 工具会把输入转换为 {@link AgentTaskRequest}，然后根据运行模式选择执行路径：</p>
 *
 * <ul>
 *     <li>同步模式：阻塞当前工具调用，等待子 Agent 完成后直接返回完整结果。</li>
 *     <li>后台模式：把任务交给 {@link SubAgentTaskManager}，立即返回任务标识，完成结果稍后通知父 Agent。</li>
 * </ul>
 *
 * <p>子 Agent 的上下文、工具集合和执行循环由 {@link AgentRuntimeFactory}
 * 创建，不直接复用父 Agent 的对话上下文。</p>
 */
public final class AgentTool implements Tool {
    /** 用于构建工具 JSON Schema 的节点工厂。 */
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** {@code agent_type} 允许使用的内置子 Agent 角色名称。 */
    private static final Set<String> AGENT_TYPES = Set.of("explore", "plan", "general-purpose");

    /** 模型调用 {@code agent} 工具时必须遵守的输入结构。 */
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();

    /** 向工具注册表暴露的元数据。 */
    private static final ToolMetadata METADATA = new ToolMetadata(
            "agent",
            "Delegate a focused task to an isolated child agent, synchronously or in the background.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.BACKGROUND_TASK),
            ToolStatus.AVAILABLE
    );

    /** 同步委派时调用的子 Agent 执行器。 */
    private final AgentTaskExecutor synchronousExecutor;

    /** 负责提交、跟踪和通知后台子 Agent 任务的管理器。 */
    private final SubAgentTaskManager taskManager;

    /**
     * 使用完整子 Agent 运行时工厂创建工具。
     *
     * @param runtimeFactory 用于创建并执行隔离子 Agent 的运行时工厂
     * @param taskManager 后台子 Agent 任务管理器
     */
    public AgentTool(AgentRuntimeFactory runtimeFactory, SubAgentTaskManager taskManager) {
        this(Objects.requireNonNull(runtimeFactory, "runtimeFactory")::run, taskManager);
    }

    /**
     * 使用可替换的同步执行器创建工具。
     *
     * <p>该构造方法便于在测试或其他装配场景中注入自定义执行逻辑。</p>
     *
     * @param synchronousExecutor 同步委派执行器
     * @param taskManager 后台子 Agent 任务管理器
     */
    public AgentTool(AgentTaskExecutor synchronousExecutor, SubAgentTaskManager taskManager) {
        this.synchronousExecutor = Objects.requireNonNull(synchronousExecutor, "synchronousExecutor");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    /**
     * 返回 {@code agent} 工具的固定元数据。
     *
     * @return 包含名称、描述、能力和可用状态的工具元数据
     */
    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    /**
     * 返回委派子 Agent 时的 JSON 输入结构。
     *
     * @return {@code agent} 工具的 JSON Schema
     */
    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    /**
     * 校验并标准化子 Agent 委派参数。
     *
     * <p>{@code description}、{@code prompt} 和 {@code agent_type} 为必填字段；
     * {@code run_in_background} 未传入时默认为 {@code false}。</p>
     *
     * @param input 模型传入的原始工具参数
     * @return 包含校验状态和标准化输入的结果
     */
    @Override
    public ValidationResult validateInput(JsonNode input) {
        return ToolInputValidation.object(input)
                .requiredString("description")
                .requiredString("prompt")
                .enumString("agent_type", AGENT_TYPES, true)
                .optionalBoolean("run_in_background")
                .custom(AgentTool::applyDefaultRunMode)
                .build();
    }

    private static void applyDefaultRunMode(JsonNode input, ValidatedInputBuilder builder) {
        if (!builder.normalized().has("run_in_background")) {
            builder.normalized().put("run_in_background", false);
        }
    }

    /**
     * 将已校验的工具参数转换为子 Agent 任务并执行。
     *
     * @param input 已经 {@link #validateInput(JsonNode)} 校验和标准化的输入
     * @param context 当前父 Agent 的工具调用上下文
     * @return 同步执行的最终结果，或后台任务的启动回执
     */
    @Override
    public ToolResult run(JsonNode input, ToolContext context) {
        // 先把对外的字符串角色转换为内部枚举。
        AgentType type = parseAgentType(input.get("agent_type").asText());

        // 根据 run_in_background 决定阻塞执行还是后台执行。
        AgentRunMode mode = input.path("run_in_background").asBoolean(false)
                ? AgentRunMode.BACKGROUND
                : AgentRunMode.SYNC;

        // 优先使用当前 turn 标识；缺失时使用 tool use 标识保留父子调用关系。
        String parentTurnId = context.turnId().orElse(null);
        if (parentTurnId == null) {
            parentTurnId = context.toolUseId().orElse("unknown-turn");
        }

        // 把委派参数与父会话、工作目录等运行信息封装为不可变请求。
        AgentTaskRequest request = AgentTaskRequest.create(
                type,
                input.get("description").asText(),
                input.get("prompt").asText(),
                context.sessionId(),
                parentTurnId,
                context.cwd().toAbsolutePath().normalize().toString(),
                mode
        );

        // 后台模式只等待任务被接收；同步模式则等待子 Agent 返回终态结果。
        return mode == AgentRunMode.BACKGROUND
                ? runInBackground(request)
                : runSynchronously(request, context);
    }

    /**
     * 向进程内任务管理器提交后台子 Agent。
     *
     * @param request 后台模式的子 Agent 请求
     * @return 包含 {@code task_N} 标识的启动回执，或提交失败结果
     */
    private ToolResult runInBackground(AgentTaskRequest request) {
        try {
            // 管理器会为任务分配 task_N，并在独立虚拟线程中执行。
            SubAgentTaskManager.Task task = taskManager.submit(request);
            return ToolResult.ok("Agent \"%s\" launched in background (task %s). "
                    .formatted(request.description(), task.id())
                    + "You will be notified when it completes.");
        } catch (RuntimeException exception) {
            return ToolResult.error("Background execution failed: " + message(exception, "unknown error"));
        }
    }

    /**
     * 在当前工具调用中同步执行子 Agent。
     *
     * @param request 同步模式的子 Agent 请求
     * @param context 用于传递取消令牌的父工具上下文
     * @return 包含执行耗时和子 Agent 输出的工具结果，或失败原因
     */
    private ToolResult runSynchronously(AgentTaskRequest request, ToolContext context) {
        long startedAt = System.nanoTime();
        try {
            // 复用父工具调用的取消令牌，使取消能够传递到子 AgentLoop。
            // 跳转到 applicationService 的AgentTaskExecutor childExecutor，进入 agentloop
            AgentRunResult result = synchronousExecutor.execute(request, context.cancellationToken());

            if (!result.successful()) {
                String error = result.error().orElse(result.stopReason().toLowerCase());
                return ToolResult.error("Agent failed: " + error);
            }

            // 成功返回时同时告知父 Agent 执行耗时和完整输出。
            String output = result.output().isEmpty() ? "(agent produced no output)" : result.output();
            long elapsedMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            return ToolResult.ok("Agent \"%s\" completed in %d.%03ds.\n\n%s".formatted(
                    request.description(), elapsedMillis / 1_000L, elapsedMillis % 1_000L, output));
        } catch (Exception exception) {
            return ToolResult.error("Agent failed: " + message(exception, "unknown error"));
        }
    }

    /**
     * 将工具 Schema 中的角色名称转换为内部枚举。
     *
     * @param value 对外暴露的角色名称
     * @return 对应的子 Agent 角色
     * @throws IllegalArgumentException 当角色名称未知时抛出
     */
    private static AgentType parseAgentType(String value) {
        return switch (value) {
            case "explore" -> AgentType.EXPLORE;
            case "plan" -> AgentType.PLAN;
            case "general-purpose" -> AgentType.GENERAL_PURPOSE;
            default -> throw new IllegalArgumentException("Unknown agent type: " + value);
        };
    }

    /**
     * 提取可供用户阅读的异常信息。
     *
     * @param exception 执行过程中捕获的异常
     * @param fallback 异常没有有效消息时使用的默认文本
     * @return 非空的错误描述
     */
    private static String message(Exception exception, String fallback) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 构建 {@code agent} 工具的 JSON Schema。
     *
     * @return 包含必填字段、角色枚举和后台开关的输入结构
     */
    private static ObjectNode createInputSchema() {
        ObjectNode schema = JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        // 定义委派任务的文本字段以及三种内置角色。
        ObjectNode properties = schema.putObject("properties");
        stringProperty(properties, "description", "Short description of the delegated task.");
        stringProperty(properties, "prompt", "Complete instructions for the child agent.");
        ObjectNode agentType = stringProperty(properties, "agent_type", "Built-in child-agent role.");
        ArrayNode choices = agentType.putArray("enum");
        choices.add("explore").add("plan").add("general-purpose");

        // 后台开关非必填，并与 validateInput 中的默认值保持一致。
        ObjectNode background = properties.putObject("run_in_background");
        background.put("type", "boolean");
        background.put("description", "Run the child agent in the background.");
        background.put("default", false);
        schema.putArray("required").add("description").add("prompt").add("agent_type");
        return schema;
    }

    /**
     * 向 Schema 的 {@code properties} 中添加字符串字段。
     *
     * @param properties Schema 的字段集合节点
     * @param name 字段名称
     * @param description 字段说明
     * @return 新建的字段 Schema，便于调用方继续追加枚举等约束
     */
    private static ObjectNode stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
