package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentType;
import minicode.agent.runtime.AgentRuntimeFactory;
import minicode.agent.task.AgentTaskExecutor;
import minicode.agent.task.AgentTaskRejectedException;
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

import java.util.Objects;
import java.util.Set;

/** 将任务委派给隔离的内置子 Agent。 */
public final class AgentTool implements AgentTaskJsonTool {
    private static final Set<String> AGENT_TYPES = Set.of("explore", "plan", "general-purpose");
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "agent",
            "Delegate a focused task to an isolated child agent, synchronously or as a read-only background task.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.BACKGROUND_TASK),
            ToolStatus.AVAILABLE
    );

    private final AgentTaskExecutor synchronousExecutor;
    private final SubAgentTaskManager taskManager;

    public AgentTool(AgentRuntimeFactory runtimeFactory, SubAgentTaskManager taskManager) {
        this(runtimeExecutor(runtimeFactory), taskManager);
    }

    public AgentTool(AgentTaskExecutor synchronousExecutor, SubAgentTaskManager taskManager) {
        this.synchronousExecutor = Objects.requireNonNull(synchronousExecutor, "synchronousExecutor");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public ToolMetadata metadata() {
        return METADATA;
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ValidationResult validateInput(JsonNode input) {
        return ToolInputValidation.object(input)
                .requiredString("description")
                .requiredString("prompt")
                .enumString("agent_type", AGENT_TYPES, true)
                .optionalBoolean("run_in_background")
                .custom((json, builder) -> {
                    if (!builder.normalized().has("run_in_background")) {
                        builder.normalized().put("run_in_background", false);
                    }
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext context) {
        AgentType type = AgentTaskToolJson.parseAgentType(input.get("agent_type").asText());
        AgentRunMode mode = input.path("run_in_background").asBoolean(false)
                ? AgentRunMode.BACKGROUND
                : AgentRunMode.SYNC;
        if (mode == AgentRunMode.BACKGROUND && type == AgentType.GENERAL_PURPOSE) {
            return ToolResult.error(AgentTaskToolJson.error("INVALID_ARGUMENT",
                    "general-purpose agents cannot run in background"));
        }
        String parentTurnId = context.turnId().orElseGet(() -> context.toolUseId().orElse("unknown-turn"));
        AgentTaskRequest request = AgentTaskRequest.create(
                type,
                input.get("description").asText(),
                input.get("prompt").asText(),
                context.sessionId(),
                parentTurnId,
                context.cwd().toAbsolutePath().normalize().toString(),
                mode
        );

        if (mode == AgentRunMode.BACKGROUND) {
            return submitBackground(request);
        }
        return runSynchronously(request, context);
    }

    private ToolResult submitBackground(AgentTaskRequest request) {
        try {
            AgentTaskSnapshot snapshot = taskManager.submit(request);
            return ToolResult.ok(AgentTaskToolJson.backgroundAccepted(snapshot));
        } catch (AgentTaskRejectedException exception) {
            return ToolResult.error(AgentTaskToolJson.error("TASK_CAPACITY_EXCEEDED",
                    messageOrDefault(exception, "Background agent capacity exceeded")));
        } catch (RuntimeException exception) {
            return ToolResult.error(AgentTaskToolJson.error("TASK_SUBMISSION_FAILED",
                    messageOrDefault(exception, "Background agent submission failed")));
        }
    }

    private ToolResult runSynchronously(AgentTaskRequest request, ToolContext context) {
        try {
            AgentRunResult result = synchronousExecutor.execute(request, context.cancellationToken());
            String json = AgentTaskToolJson.synchronousResult(
                    request.taskId(), request.agentId(), request.type(), result);
            return result.successful() ? ToolResult.ok(json) : ToolResult.error(json);
        } catch (Exception exception) {
            return ToolResult.error(AgentTaskToolJson.error("AGENT_EXECUTION_FAILED",
                    messageOrDefault(exception, "Child agent execution failed")));
        }
    }

    private static String messageOrDefault(Exception exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static AgentTaskExecutor runtimeExecutor(AgentRuntimeFactory runtimeFactory) {
        AgentRuntimeFactory actualRuntimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        return actualRuntimeFactory::run;
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = AgentTaskToolJson.JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = schema.putObject("properties");
        stringProperty(properties, "description", "Short description of the delegated task.");
        stringProperty(properties, "prompt", "Complete instructions for the child agent.");
        ObjectNode agentType = stringProperty(properties, "agent_type", "Built-in child-agent role.");
        ArrayNode choices = agentType.putArray("enum");
        choices.add("explore").add("plan").add("general-purpose");
        ObjectNode background = properties.putObject("run_in_background");
        background.put("type", "boolean");
        background.put("description", "Run a read-only explore or plan agent in the background.");
        background.put("default", false);

        schema.putArray("required").add("description").add("prompt").add("agent_type");
        return schema;
    }

    private static ObjectNode stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
