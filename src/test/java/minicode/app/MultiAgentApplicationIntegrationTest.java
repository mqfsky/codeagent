package minicode.app;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.event.AgentTaskEvent;
import minicode.agent.runtime.ModelAdapterFactory;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.ForkableModelAdapter;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AgentNotificationMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.permissions.model.PermissionDecision;
import minicode.permissions.model.PermissionPromptResult;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentApplicationIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void compatibilityAdapterForksAgainstParentAndFilteredChildRegistries() throws Exception {
        Path home = tempDir.resolve("fork-home");
        Path cwd = tempDir.resolve("fork-workspace");
        Files.createDirectories(cwd);
        RecordingForkableAdapter adapter = new RecordingForkableAdapter();

        ApplicationServices services = ApplicationServices.create(
                home, cwd, "fork-session", adapter, AgentEventSink.noOp(),
                PermissionPromptHandler.unavailable());
        try {
            ToolResult result = services.toolRegistry().execute(new ToolCall(
                    "sync-explore", "agent", JsonNodeFactory.instance.objectNode()
                    .put("description", "inspect registry")
                    .put("prompt", "inspect only")
                    .put("agent_type", "explore")
                    .put("run_in_background", false)),
                    new ToolContext(cwd, "fork-session", Optional.of("parent-turn"),
                            Optional.of("sync-explore")));

            assertTrue(!result.error(), result.content());
            assertEquals(2, adapter.registries.size());
            ToolRegistry parent = adapter.registries.getFirst();
            ToolRegistry child = adapter.registries.getLast();
            assertTrue(parent.find("agent").isPresent());
            assertTrue(parent.find("task_output").isPresent());
            assertTrue(child.find("read_file").isPresent());
            assertTrue(child.find("agent").isEmpty());
            assertTrue(child.find("task_output").isEmpty());
            assertTrue(child.list().stream().allMatch(tool ->
                    tool.metadata().capabilities().equals(java.util.Set.of(ToolCapability.READ))));
            assertTrue(parent != child);
        } finally {
            services.close();
        }
    }

    @Test
    void launchesTwoBackgroundExplorersAndInjectsBothDurableResultsIntoCurrentTurn() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(cwd);
        CountDownLatch bothChildrenStarted = new CountDownLatch(2);
        List<AgentTaskEvent> taskEvents = new CopyOnWriteArrayList<>();
        AtomicInteger adapterCreations = new AtomicInteger();
        ModelAdapter behavior = messages -> {
            if (isChild(messages)) {
                bothChildrenStarted.countDown();
                try {
                    if (!bothChildrenStarted.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("background children did not overlap");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("child interrupted", exception);
                }
                String prompt = ((minicode.core.message.UserMessage) messages.get(1)).content();
                return new AssistantStep("result for " + prompt, AssistantKind.FINAL);
            }

            boolean alreadyDelegated = messages.stream().anyMatch(message ->
                    message instanceof AssistantToolCallMessage call && call.toolName().equals("agent"));
            if (!alreadyDelegated) {
                return new ToolCallsStep(List.of(
                        backgroundExploreCall("agent-call-1", "first area"),
                        backgroundExploreCall("agent-call-2", "second area")
                ), Optional.empty(), ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
            }

            List<AgentNotificationMessage> notifications = messages.stream()
                    .filter(AgentNotificationMessage.class::isInstance)
                    .map(AgentNotificationMessage.class::cast)
                    .toList();
            if (notifications.size() < 2) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new AssistantStep("Waiting for delegated results", AssistantKind.PROGRESS);
            }
            assertTrue(notifications.stream().anyMatch(message -> message.content().contains("first area")));
            assertTrue(notifications.stream().anyMatch(message -> message.content().contains("second area")));
            return new AssistantStep("summarized two background results", AssistantKind.FINAL);
        };
        ModelAdapterFactory adapterFactory = registry -> {
            adapterCreations.incrementAndGet();
            return behavior::next;
        };

        ApplicationServices services = ApplicationServices.createWithModelAdapterFactoryAndAgentTaskEventSink(
                home, cwd, "session-a", adapterFactory, AgentEventSink.noOp(), taskEvents::add,
                PermissionPromptHandler.unavailable());
        try {
            assertEquals(List.of("agent", "task_list", "task_status", "task_output", "task_cancel"),
                    services.toolRegistry().list().stream()
                            .map(tool -> tool.metadata().name())
                            .filter(name -> name.equals("agent") || name.startsWith("task_"))
                            .toList());

            AgentTurnResult result = services.runTurn(services.turnRequest(
                    List.of(new minicode.core.message.UserMessage("inspect both areas")), 20));

            assertEquals("summarized two background results",
                    ((minicode.core.message.AssistantMessage) result.messages().getLast()).content());
            assertEquals(2, result.messages().stream().filter(AgentNotificationMessage.class::isInstance).count());
            var snapshots = services.subAgentTaskManager().orElseThrow().list(cwd.toString(), "session-a", 100);
            assertEquals(2, snapshots.size());
            assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.status() == AgentTaskStatus.COMPLETED));
            assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.notificationDelivered()));
            assertTrue(snapshots.stream().allMatch(snapshot -> snapshot.output().orElseThrow().startsWith("result for")));
            assertEquals(3, adapterCreations.get(), "one parent adapter and one fresh adapter per child");
            assertEquals(snapshots.stream().map(snapshot -> snapshot.taskId()).collect(java.util.stream.Collectors.toSet()),
                    taskEvents.stream().map(AgentTaskEvent::taskId).flatMap(Optional::stream)
                            .collect(java.util.stream.Collectors.toSet()));
        } finally {
            services.close();
        }
    }

    @Test
    void backgroundAgentCannotUseInteractiveOrMutatingToolsAndOutsideReadNeverPrompts() throws Exception {
        Path home = tempDir.resolve("isolated-home");
        Path cwd = tempDir.resolve("isolated-workspace");
        Files.createDirectories(cwd);
        Path secret = tempDir.resolve("outside-secret.txt");
        Files.writeString(secret, "TOP_SECRET_CONTENT");
        AtomicInteger parentPermissionPrompts = new AtomicInteger();
        AtomicReference<String> deliveredContent = new AtomicReference<>();

        ModelAdapter adapter = messages -> {
            if (isChild(messages)) {
                List<ToolResultMessage> results = messages.stream()
                        .filter(ToolResultMessage.class::isInstance)
                        .map(ToolResultMessage.class::cast)
                        .toList();
                if (results.isEmpty()) {
                    return new ToolCallsStep(List.of(
                            new ToolCall("outside-read", "read_file",
                                    JsonNodeFactory.instance.objectNode().put("path", secret.toString())),
                            new ToolCall("write", "write_file", JsonNodeFactory.instance.objectNode()),
                            new ToolCall("command", "run_command", JsonNodeFactory.instance.objectNode()),
                            new ToolCall("interactive", "ask_user", JsonNodeFactory.instance.objectNode()),
                            new ToolCall("nested", "agent", JsonNodeFactory.instance.objectNode()),
                            new ToolCall("task", "task_output", JsonNodeFactory.instance.objectNode()),
                            new ToolCall("mcp", "mcp__server__tool", JsonNodeFactory.instance.objectNode())
                    ), Optional.empty(), ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
                }
                return new AssistantStep(results.stream()
                        .map(result -> result.toolName() + "=" + result.content())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElseThrow(), AssistantKind.FINAL);
            }

            boolean delegated = messages.stream().anyMatch(message ->
                    message instanceof AssistantToolCallMessage call && call.toolName().equals("agent"));
            if (!delegated) {
                return new ToolCallsStep(List.of(backgroundExploreCall("background-isolation", "probe isolation")),
                        Optional.empty(), ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
            }
            Optional<AgentNotificationMessage> notification = messages.stream()
                    .filter(AgentNotificationMessage.class::isInstance)
                    .map(AgentNotificationMessage.class::cast)
                    .findFirst();
            if (notification.isEmpty()) {
                pauseBriefly();
                return new AssistantStep("Waiting for isolation result", AssistantKind.PROGRESS);
            }
            String content = notification.orElseThrow().content();
            deliveredContent.set(content);
            return new AssistantStep("background isolation verified", AssistantKind.FINAL);
        };

        PermissionPromptHandler parentPrompt = request -> {
            parentPermissionPrompts.incrementAndGet();
            return PermissionPromptResult.allow(PermissionDecision.ALLOW_ONCE);
        };
        ApplicationServices services = ApplicationServices.create(
                home, cwd, "session-isolation", adapter, AgentEventSink.noOp(), parentPrompt);
        try {
            AgentTurnResult result = services.runTurn(services.turnRequest(
                    List.of(new minicode.core.message.UserMessage("verify background isolation")), 20));
            assertEquals(AgentTurnStopReason.FINAL, result.stopReason(),
                    () -> "messages=" + result.messages() + " details=" + result.stopDetails());
            assertEquals("background isolation verified",
                    ((minicode.core.message.AssistantMessage) result.messages().getLast()).content());
            assertEquals(0, parentPermissionPrompts.get());
            String content = deliveredContent.get();
            assertTrue(content.contains("Unknown tool: write_file"), content);
            assertTrue(content.contains("Unknown tool: run_command"), content);
            assertTrue(content.contains("Unknown tool: ask_user"), content);
            assertTrue(content.contains("Unknown tool: agent"), content);
            assertTrue(content.contains("Unknown tool: task_output"), content);
            assertTrue(content.contains("Unknown tool: mcp__server__tool"), content);
            assertTrue(content.contains("Permission denied"), content);
            assertTrue(!content.contains("TOP_SECRET_CONTENT"), content);
        } finally {
            services.close();
        }
    }

    @Test
    void synchronousGeneralPurposeUsesParentPermissionLifecycleForWrites() throws Exception {
        Path home = tempDir.resolve("sync-home");
        Path cwd = tempDir.resolve("sync-workspace");
        Files.createDirectories(cwd);
        AtomicInteger permissionPrompts = new AtomicInteger();
        ModelAdapter adapter = messages -> {
            if (isChild(messages)) {
                Optional<ToolResultMessage> writeResult = messages.stream()
                        .filter(ToolResultMessage.class::isInstance)
                        .map(ToolResultMessage.class::cast)
                        .findFirst();
                if (writeResult.isEmpty()) {
                    return new ToolCallsStep(List.of(new ToolCall("child-write", "write_file",
                            JsonNodeFactory.instance.objectNode()
                                    .put("path", "created-by-child.txt")
                                    .put("content", "written by synchronous child"))),
                            Optional.empty(), ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
                }
                return new AssistantStep("child write: " + writeResult.orElseThrow().content(), AssistantKind.FINAL);
            }

            boolean delegated = messages.stream().anyMatch(message ->
                    message instanceof AssistantToolCallMessage call && call.toolName().equals("agent"));
            if (!delegated) {
                return new ToolCallsStep(List.of(new ToolCall("sync-general", "agent",
                        JsonNodeFactory.instance.objectNode()
                                .put("description", "write delegated file")
                                .put("prompt", "create the requested file")
                                .put("agent_type", "general-purpose")
                                .put("run_in_background", false))),
                        Optional.empty(), ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
            }
            return new AssistantStep("synchronous delegation completed", AssistantKind.FINAL);
        };
        PermissionPromptHandler promptHandler = request -> {
            permissionPrompts.incrementAndGet();
            return PermissionPromptResult.allow(PermissionDecision.ALLOW_TURN);
        };

        ApplicationServices services = ApplicationServices.create(
                home, cwd, "session-sync", adapter, AgentEventSink.noOp(), promptHandler);
        try {
            AgentTurnResult result = services.runTurn(services.turnRequest(
                    List.of(new minicode.core.message.UserMessage("delegate a write")), 10));
            assertEquals("synchronous delegation completed",
                    ((minicode.core.message.AssistantMessage) result.messages().getLast()).content());
            assertEquals("written by synchronous child", Files.readString(cwd.resolve("created-by-child.txt")));
            assertEquals(1, permissionPrompts.get());
        } finally {
            services.close();
        }
    }

    private static boolean isChild(List<ChatMessage> messages) {
        return !messages.isEmpty()
                && messages.getFirst() instanceof SystemMessage system
                && system.content().contains("CodeAgent child agent with the built-in role");
    }

    private static ToolCall backgroundExploreCall(String id, String prompt) {
        return new ToolCall(id, "agent", JsonNodeFactory.instance.objectNode()
                .put("description", "explore " + prompt)
                .put("prompt", prompt)
                .put("agent_type", "explore")
                .put("run_in_background", true));
    }

    private static void pauseBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingForkableAdapter implements ForkableModelAdapter {
        private final List<ToolRegistry> registries = new CopyOnWriteArrayList<>();

        @Override
        public minicode.core.step.AgentStep next(List<ChatMessage> messages) {
            return new AssistantStep("original adapter should not be used", AssistantKind.FINAL);
        }

        @Override
        public ModelAdapter fork(ToolRegistry toolRegistry) {
            registries.add(toolRegistry);
            return messages -> new AssistantStep("forked adapter result", AssistantKind.FINAL);
        }
    }
}
