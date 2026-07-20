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

/** 从后台子 Agent 的结果中读取指定长度的字符片段。 */
public final class TaskOutputTool implements AgentTaskJsonTool {
    public static final int DEFAULT_LIMIT = 20_000;
    public static final int MAX_LIMIT = 50_000;

    private static final ObjectNode INPUT_SCHEMA = createInputSchema();
    private static final ToolMetadata METADATA = new ToolMetadata(
            "task_output",
            "Read a character range from a background agent task result.",
            INPUT_SCHEMA,
            ToolOrigin.BUILTIN,
            Set.of(ToolCapability.BACKGROUND_TASK),
            ToolStatus.AVAILABLE
    );

    private final SubAgentTaskManager taskManager;

    public TaskOutputTool(SubAgentTaskManager taskManager) {
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
                .optionalInteger("offset", 0, Integer.MAX_VALUE)
                .optionalInteger("limit", 1, MAX_LIMIT)
                .custom((json, builder) -> {
                    if (!builder.normalized().has("offset")) {
                        builder.normalized().put("offset", 0);
                    }
                    if (!builder.normalized().has("limit")) {
                        builder.normalized().put("limit", DEFAULT_LIMIT);
                    }
                })
                .build();
    }

    @Override
    public ToolResult run(JsonNode input, ToolContext context) {
        try {
            Optional<AgentTaskSnapshot> snapshot = TaskStatusTool.findScoped(
                    taskManager, input.get("task_id").asText(), context);
            if (snapshot.isEmpty()) {
                return TaskStatusTool.taskNotFound();
            }
            return ToolResult.ok(AgentTaskToolJson.taskOutput(
                    snapshot.orElseThrow(), input.get("offset").asInt(), input.get("limit").asInt()));
        } catch (RuntimeException exception) {
            return ToolResult.error(AgentTaskToolJson.error(
                    "TASK_OUTPUT_FAILED", exception, "Unable to read background agent task output"));
        }
    }

    private static ObjectNode createInputSchema() {
        ObjectNode schema = AgentTaskToolJson.JSON.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        stringProperty(properties, "task_id", "Background task identifier returned by agent.");
        integerProperty(properties, "offset", "Zero-based character offset.", 0, Integer.MAX_VALUE, 0);
        integerProperty(properties, "limit", "Maximum number of characters to return.", 1, MAX_LIMIT,
                DEFAULT_LIMIT);
        schema.putArray("required").add("task_id");
        return schema;
    }

    private static void stringProperty(ObjectNode properties, String name, String description) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "string");
        property.put("description", description);
    }

    private static void integerProperty(ObjectNode properties,
                                        String name,
                                        String description,
                                        int minimum,
                                        int maximum,
                                        int defaultValue) {
        ObjectNode property = properties.putObject(name);
        property.put("type", "integer");
        property.put("description", description);
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        property.put("default", defaultValue);
    }
}
