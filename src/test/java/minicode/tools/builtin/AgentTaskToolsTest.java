package minicode.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;
import minicode.agent.runtime.AgentRuntimeFactory;
import minicode.agent.task.AgentInbox;
import minicode.agent.task.AgentTaskStore;
import minicode.agent.task.SubAgentTaskManager;
import minicode.core.turn.CancellationPhase;
import minicode.model.MockModelAdapter;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void agentSchemaValidationAndSyncResultUsePublicNames() throws Exception {
        try (ManagerFixture fixture = fixture((request, token) -> AgentRunResult.completed("background", "FINAL"))) {
            AgentRuntimeFactory runtimeFactory = new AgentRuntimeFactory(
                    new ToolRegistry(), ignored -> new MockModelAdapter("sync answer"));
            AgentTool tool = new AgentTool(runtimeFactory, fixture.manager);

            assertEquals("agent", tool.metadata().name());
            assertEquals("general-purpose",
                    tool.inputSchema().at("/properties/agent_type/enum/2").asText());

            ValidationResult validation = tool.validateInput(agentInput("general-purpose", false));
            assertTrue(validation.valid());
            assertFalse(validation.normalizedInput().orElseThrow().get("run_in_background").asBoolean());

            ToolResult result = tool.run(validation.normalizedInput().orElseThrow(), context("session-a"));
            JsonNode json = MAPPER.readTree(result.content());
            assertFalse(result.error());
            assertEquals("general-purpose", json.get("agent_type").asText());
            assertEquals("sync", json.get("run_mode").asText());
            assertEquals("COMPLETED", json.get("status").asText());
            assertEquals("sync answer", json.get("output").asText());
        }
    }

    @Test
    void agentRejectsGeneralPurposeBackgroundAsParameterError() {
        try (ManagerFixture fixture = fixture((request, token) -> AgentRunResult.completed("unused", "FINAL"))) {
            AgentTool tool = new AgentTool(runtimeFactory(), fixture.manager);
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);

            ValidationResult validation = tool.validateInput(agentInput("general-purpose", true));
            ToolResult result = registry.execute(
                    new ToolCall("agent-call", "agent", agentInput("general-purpose", true)),
                    context("session-a"));
            JsonNode json = read(result);

            assertTrue(validation.valid());
            assertTrue(result.error());
            assertEquals("INVALID_ARGUMENT", json.at("/error/code").asText());
            assertEquals("general-purpose agents cannot run in background",
                    json.at("/error/message").asText());
        }
    }

    @Test
    void registryValidationFailuresPreserveStableJsonContract() {
        try (ManagerFixture fixture = fixture((request, token) -> AgentRunResult.completed("unused", "FINAL"))) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new TaskStatusTool(fixture.manager));

            ToolResult result = registry.execute(new ToolCall(
                    "status-call", "task_status", JsonNodeFactory.instance.objectNode()), context("session-a"));
            JsonNode json = read(result);

            assertTrue(result.error());
            assertEquals("INVALID_ARGUMENT", json.at("/error/code").asText());
            assertTrue(json.at("/error/message").asText().contains("task_id"));
        }
    }

    @Test
    void backgroundAgentReturnsQueuedStableJson() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (ManagerFixture fixture = fixture((request, token) -> {
            release.await(5, TimeUnit.SECONDS);
            return AgentRunResult.completed("done", "FINAL");
        })) {
            AgentTool tool = new AgentTool(runtimeFactory(), fixture.manager);
            ValidationResult validation = tool.validateInput(agentInput("explore", true));

            ToolResult result = tool.run(validation.normalizedInput().orElseThrow(), context("session-a"));
            JsonNode json = MAPPER.readTree(result.content());

            assertFalse(result.error());
            assertEquals("explore", json.get("agent_type").asText());
            assertEquals("background", json.get("run_mode").asText());
            assertEquals("QUEUED", json.get("status").asText());
            assertFalse(json.get("task_id").asText().isBlank());
            release.countDown();
        }
    }

    @Test
    void listIsScopedToCwdAndSessionAndLimitedToNewestHundred() throws Exception {
        try (ManagerFixture fixture = fixture((request, token) -> AgentRunResult.completed("unused", "FINAL"))) {
            String cwd = normalizedCwd();
            Instant base = Instant.parse("2026-07-20T00:00:00Z");
            for (int index = 0; index < 105; index++) {
                fixture.store.save(completed("task-" + index, cwd, "session-a",
                        base.plusSeconds(index), "output-" + index));
            }
            fixture.store.save(completed("foreign", cwd, "session-b", base.plusSeconds(1000), "secret"));

            ToolResult result = new TaskListTool(fixture.manager).run(
                    JsonNodeFactory.instance.objectNode(), context("session-a"));
            JsonNode json = MAPPER.readTree(result.content());

            assertFalse(result.error());
            assertEquals(cwd, json.get("cwd").asText());
            assertEquals("session-a", json.get("session_id").asText());
            assertEquals(100, json.get("tasks").size());
            assertEquals("task-104", json.at("/tasks/0/task_id").asText());
            assertFalse(result.content().contains("foreign"));
            assertFalse(json.at("/tasks/0").has("output"));
            assertEquals("output-104".length(), json.at("/tasks/0/output_chars").asInt());
        }
    }

    @Test
    void statusUsesIdenticalNotFoundResponseForUnknownAndOtherSession() {
        try (ManagerFixture fixture = fixture((request, token) -> AgentRunResult.completed("unused", "FINAL"))) {
            fixture.store.save(completed("known", normalizedCwd(), "session-b", Instant.now(), "secret"));
            TaskStatusTool tool = new TaskStatusTool(fixture.manager);

            ToolResult unknown = tool.run(taskIdInput("missing"), context("session-a"));
            ToolResult foreign = tool.run(taskIdInput("known"), context("session-a"));

            assertTrue(unknown.error());
            assertTrue(foreign.error());
            assertEquals(unknown.content(), foreign.content());
            assertEquals("TASK_NOT_FOUND", read(unknown).at("/error/code").asText());
            assertEquals("任务不存在", read(unknown).at("/error/message").asText());
        }
    }

    @Test
    void outputDefaultsToTwentyThousandAndSupportsBoundedWindows() {
        try (ManagerFixture fixture = fixture((request, token) -> AgentRunResult.completed("unused", "FINAL"))) {
            String output = "x".repeat(25_050);
            fixture.store.save(completed("large", normalizedCwd(), "session-a", Instant.now(), output));
            TaskOutputTool tool = new TaskOutputTool(fixture.manager);

            ValidationResult defaults = tool.validateInput(taskIdInput("large"));
            assertTrue(defaults.valid());
            ToolResult first = tool.run(defaults.normalizedInput().orElseThrow(), context("session-a"));
            JsonNode firstJson = read(first);
            assertEquals(20_000, firstJson.get("output").asText().length());
            assertEquals(20_000, firstJson.get("next_offset").asInt());
            assertTrue(firstJson.get("truncated").asBoolean());

            ObjectNode rangeInput = taskIdInput("large").put("offset", 25_000).put("limit", 100);
            ValidationResult range = tool.validateInput(rangeInput);
            ToolResult tail = tool.run(range.normalizedInput().orElseThrow(), context("session-a"));
            assertEquals(50, read(tail).get("output").asText().length());
            assertFalse(read(tail).get("truncated").asBoolean());

            ValidationResult tooLarge = tool.validateInput(taskIdInput("large").put("limit", 50_001));
            assertFalse(tooLarge.valid());
        }
    }

    @Test
    void cancelTransitionsRunningTaskAndUnknownTasksStayHidden() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (ManagerFixture fixture = fixture((request, token) -> {
            started.countDown();
            while (true) {
                token.throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                Thread.sleep(5);
            }
        })) {
            AgentTaskRequest request = new AgentTaskRequest(
                    "cancel-me", "agent-cancel-me", AgentType.EXPLORE, "inspect", "prompt",
                    "session-a", "turn-a", normalizedCwd(), AgentRunMode.BACKGROUND, Instant.now());
            fixture.manager.submit(request);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            TaskCancelTool tool = new TaskCancelTool(fixture.manager);
            ValidationResult validation = tool.validateInput(taskIdInput("cancel-me").put("reason", "stop now"));

            ToolResult cancelled = tool.run(validation.normalizedInput().orElseThrow(), context("session-a"));
            JsonNode json = read(cancelled);
            assertFalse(cancelled.error());
            assertEquals("CANCELLED", json.get("status").asText());
            assertTrue(json.get("cancelled").asBoolean());
            assertTrue(json.get("changed").asBoolean());

            ToolResult unknown = tool.run(tool.validateInput(taskIdInput("missing"))
                    .normalizedInput().orElseThrow(), context("session-a"));
            assertEquals(TaskStatusTool.taskNotFound().content(), unknown.content());
        }
    }

    private ManagerFixture fixture(minicode.agent.task.AgentTaskExecutor executor) {
        AgentTaskStore store = new AgentTaskStore(tempDir.resolve("tasks-" + System.nanoTime()));
        AgentInbox inbox = new AgentInbox(store);
        return new ManagerFixture(store, new SubAgentTaskManager(store, inbox, executor));
    }

    private AgentRuntimeFactory runtimeFactory() {
        return new AgentRuntimeFactory(new ToolRegistry(), ignored -> new MockModelAdapter("answer"));
    }

    private ToolContext context(String sessionId) {
        return new ToolContext(tempDir, sessionId, Optional.of("turn-a"), Optional.of("tool-a"));
    }

    private String normalizedCwd() {
        return tempDir.toAbsolutePath().normalize().toString();
    }

    private static ObjectNode agentInput(String type, boolean background) {
        return JsonNodeFactory.instance.objectNode()
                .put("description", "inspect project")
                .put("prompt", "find the relevant files")
                .put("agent_type", type)
                .put("run_in_background", background);
    }

    private static ObjectNode taskIdInput(String taskId) {
        return JsonNodeFactory.instance.objectNode().put("task_id", taskId);
    }

    private static AgentTaskSnapshot completed(String taskId,
                                               String cwd,
                                               String sessionId,
                                               Instant submittedAt,
                                               String output) {
        AgentTaskRequest request = new AgentTaskRequest(
                taskId, "agent-" + taskId, AgentType.EXPLORE, "inspect", "prompt",
                sessionId, "turn-a", cwd, AgentRunMode.BACKGROUND, submittedAt);
        AgentTaskSnapshot running = AgentTaskSnapshot.queued(request).transitionTo(
                AgentTaskStatus.RUNNING, submittedAt.plusMillis(1), Optional.empty(), Optional.empty());
        return running.transitionTo(AgentTaskStatus.COMPLETED, submittedAt.plusMillis(2),
                Optional.of(output), Optional.empty());
    }

    private static JsonNode read(ToolResult result) {
        try {
            return MAPPER.readTree(result.content());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record ManagerFixture(AgentTaskStore store, SubAgentTaskManager manager) implements AutoCloseable {
        @Override
        public void close() {
            manager.close();
        }
    }
}
