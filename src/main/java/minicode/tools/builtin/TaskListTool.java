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

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 列出当前工作区和 Session 中最近的后台子 Agent 任务。 */
public final class TaskListTool implements AgentTaskJsonTool {
    private static final int LIST_LIMIT = 100;
    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "task_list",
            "List up to 100 recent background agent tasks for the current workspace and session.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.BACKGROUND_TASK),
            ToolStatus.AVAILABLE
    );

    private final SubAgentTaskManager taskManager;

    public TaskListTool(SubAgentTaskManager taskManager) {
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
        return ToolInputValidation.object(input).build();
    }

    @Override
    public ToolResult run(JsonNode normalizedInput, ToolContext context) {
        String cwd = context.cwd().toAbsolutePath().normalize().toString();
        try {
            List<AgentTaskSnapshot> tasks = taskManager.list(cwd, context.sessionId(), LIST_LIMIT);
            return ToolResult.ok(AgentTaskToolJson.taskList(cwd, context.sessionId(), tasks));
        } catch (RuntimeException exception) {
            return ToolResult.error(AgentTaskToolJson.error(
                    "TASK_LIST_FAILED", exception, "Unable to list background agent tasks"));
        }
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = AgentTaskToolJson.JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }
}
