package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentTaskSnapshot;
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
import java.util.Optional;
import java.util.Set;

/** 读取指定后台子 Agent 任务的持久化快照。 */
public final class TaskStatusTool implements AgentTaskJsonTool {
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "task_status",
            "Read the current status and metadata of a background agent task.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.BACKGROUND_TASK),
            ToolStatus.AVAILABLE
    );

    private final SubAgentTaskManager taskManager;

    public TaskStatusTool(SubAgentTaskManager taskManager) {
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
        return ToolInputValidation.object(input).requiredString("task_id").build();
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext context) {
        try {
            Optional<AgentTaskSnapshot> snapshot = findScoped(taskManager, input.get("task_id").asText(), context);
            return snapshot
                    .map(value -> ToolResult.ok(AgentTaskToolJson.taskStatus(value)))
                    .orElseGet(TaskStatusTool::taskNotFound);
        } catch (RuntimeException exception) {
            return ToolResult.error(AgentTaskToolJson.error(
                    "TASK_STATUS_FAILED", exception, "Unable to read background agent task status"));
        }
    }

    static Optional<AgentTaskSnapshot> findScoped(SubAgentTaskManager taskManager,
                                                  String taskId,
                                                  ToolContext context) {
        return taskManager.find(taskId, context.cwd().toAbsolutePath().normalize().toString(), context.sessionId());
    }

    static ToolResult taskNotFound() {
        return ToolResult.error(AgentTaskToolJson.error("TASK_NOT_FOUND", "任务不存在"));
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = AgentTaskToolJson.JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode taskId = schema.putObject("properties").putObject("task_id");
        taskId.put("type", "string");
        taskId.put("description", "Background task identifier returned by agent.");
        schema.putArray("required").add("task_id");
        return schema;
    }
}
