package minicode.tools.builtin;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.task.AgentTaskExecutor;
import minicode.agent.task.SubAgentTaskManager;
import minicode.core.turn.CancellationToken;
import minicode.tools.api.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolTest {
    @TempDir
    Path tempDir;

    @Test
    void synchronousAgentReturnsInlineResult() {
        try (SubAgentTaskManager manager = managerReturning(AgentRunResult.completed("unused", "FINAL"))) {
            AgentTool tool = new AgentTool(new FixedResultTaskExecutor(
                    AgentRunResult.completed("同步结果", "FINAL")), manager);
            ObjectNode input = input("explore", false);

            var result = tool.run(tool.validateInput(input).normalizedInput().orElseThrow(), context());

            assertFalse(result.error());
            assertTrue(result.content().contains("completed"));
            assertTrue(result.content().contains("同步结果"));
        }
    }

    @Test
    void synchronousMaxStepsResultIsReturnedAsToolError() {
        try (SubAgentTaskManager manager = managerReturning(AgentRunResult.completed("unused", "FINAL"))) {
            AgentTool tool = new AgentTool(new FixedResultTaskExecutor(AgentRunResult.failed(
                    "partial", "MAX_STEPS", "Child agent reached maximum steps")), manager);

            var result = tool.run(tool.validateInput(input("explore", false))
                    .normalizedInput().orElseThrow(), context());

            assertTrue(result.error());
            assertTrue(result.content().contains("maximum steps"));
        }
    }

    @Test
    void allBuiltInRolesCanRunInBackground() {
        try (SubAgentTaskManager manager = managerReturning(AgentRunResult.completed("后台结果", "FINAL"))) {
            AgentTool tool = new AgentTool(new FixedResultTaskExecutor(
                    AgentRunResult.completed("sync", "FINAL")), manager);

            var result = tool.run(tool.validateInput(input("general-purpose", true))
                    .normalizedInput().orElseThrow(), context());

            assertFalse(result.error());
            assertTrue(result.content().contains("launched in background"));
            assertTrue(result.content().contains("task_1"));
        }
    }

    @Test
    void backgroundDefaultsToFalse() {
        try (SubAgentTaskManager manager = managerReturning(AgentRunResult.completed("unused", "FINAL"))) {
            AgentTool tool = new AgentTool(new FixedResultTaskExecutor(
                    AgentRunResult.completed("sync", "FINAL")), manager);
            ObjectNode input = input("plan", false);
            input.remove("run_in_background");

            var validation = tool.validateInput(input);

            assertTrue(validation.valid());
            assertFalse(validation.normalizedInput().orElseThrow().get("run_in_background").asBoolean());
        }
    }

    private ObjectNode input(String type, boolean background) {
        return JsonNodeFactory.instance.objectNode()
                .put("description", "inspect project")
                .put("prompt", "find relevant files")
                .put("agent_type", type)
                .put("run_in_background", background);
    }

    private ToolContext context() {
        return new ToolContext(tempDir, "session", Optional.of("turn"), Optional.of("tool"));
    }

    private static SubAgentTaskManager managerReturning(AgentRunResult result) {
        return new SubAgentTaskManager(new FixedResultTaskExecutor(result));
    }

    private static final class FixedResultTaskExecutor implements AgentTaskExecutor {
        private final AgentRunResult result;

        private FixedResultTaskExecutor(AgentRunResult result) {
            this.result = result;
        }

        @Override
        public AgentRunResult execute(AgentTaskRequest request, CancellationToken cancellationToken) {
            return result;
        }
    }
}
