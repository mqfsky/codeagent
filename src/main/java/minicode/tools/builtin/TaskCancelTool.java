package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.task.CancelResult;
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

/** 请求取消排队中或运行中的后台子 Agent 任务。 */
public final class TaskCancelTool implements AgentTaskJsonTool {
    private static final String DEFAULT_REASON = "Cancelled by parent agent";
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "task_cancel",
            "Cancel a queued or running background agent task.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.BACKGROUND_TASK),
            ToolStatus.AVAILABLE
    );

    private final SubAgentTaskManager taskManager;

    public TaskCancelTool(SubAgentTaskManager taskManager) {
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
                .requiredString("task_id")
                .optionalString("reason")
                .custom((json, builder) -> {
                    if (!builder.normalized().has("reason")) {
                        builder.normalized().put("reason", DEFAULT_REASON);
                    }
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext context) {
        String taskId = input.get("task_id").asText();
        try {
            if (TaskStatusTool.findScoped(taskManager, taskId, context).isEmpty()) {
                return TaskStatusTool.taskNotFound();
            }
            CancelResult result = taskManager.cancel(
                    taskId,
                    context.cwd().toAbsolutePath().normalize().toString(),
                    context.sessionId(),
                    input.get("reason").asText());
            return ToolResult.ok(AgentTaskToolJson.taskCancelled(result.snapshot(), result.changed()));
        } catch (IllegalArgumentException exception) {
            // 并发导致的作用域变化或任务移除，有意与未知任务保持相同表现。
            return TaskStatusTool.taskNotFound();
        } catch (RuntimeException exception) {
            return ToolResult.error(AgentTaskToolJson.error(
                    "TASK_CANCEL_FAILED", exception, "Unable to cancel background agent task"));
        }
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = AgentTaskToolJson.JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        stringProperty(properties, "task_id", "Background task identifier returned by agent.");
        ObjectNode reason = stringProperty(properties, "reason", "Optional cancellation reason.");
        reason.put("default", DEFAULT_REASON);
        schema.putArray("required").add("task_id");
        return schema;
    }

    private static ObjectNode stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
