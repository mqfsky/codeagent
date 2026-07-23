package minicode.app;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.runtime.ModelAdapterFactory;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.ForkableModelAdapter;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.permissions.api.PermissionPromptHandler;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAgentApplicationIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void parentRegistersOnlyAgentToolAndChildUsesFilteredRegistry() throws Exception {
        Path home = tempDir.resolve("fork-home");
        Path cwd = tempDir.resolve("fork-workspace");
        Files.createDirectories(cwd);
        RecordingForkableAdapter adapter = new RecordingForkableAdapter();

        ApplicationServices services = ApplicationServices.create(
                home, cwd, "fork-session", adapter, AgentEventSink.noOp(),
                PermissionPromptHandler.unavailable());
        try {
            var result = services.toolRegistry().execute(new ToolCall(
                            "sync-explore", "agent", JsonNodeFactory.instance.objectNode()
                            .put("description", "inspect registry")
                            .put("prompt", "inspect only")
                            .put("agent_type", "explore")
                            .put("run_in_background", false)),
                    new ToolContext(cwd, "fork-session", Optional.of("parent-turn"),
                            Optional.of("sync-explore")));

            assertFalse(result.error(), result.content());
            assertEquals(List.of("agent"), services.toolRegistry().list().stream()
                    .map(tool -> tool.metadata().name())
                    .filter(name -> name.equals("agent") || name.startsWith("task_"))
                    .toList());
            assertEquals(2, adapter.registries.size());
            ToolRegistry parent = adapter.registries.getFirst();
            ToolRegistry child = adapter.registries.getLast();
            assertTrue(parent.find("agent").isPresent());
            assertTrue(child.find("read_file").isPresent());
            assertTrue(child.find("run_command").isPresent());
            assertTrue(child.find("agent").isEmpty());
            assertTrue(child.find("write_file").isEmpty());
        } finally {
            services.close();
        }
    }

    @Test
    void twoBackgroundExplorersReturnTransientNotificationsToParent() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("workspace");
        Files.createDirectories(cwd);
        CountDownLatch bothChildrenStarted = new CountDownLatch(2);
        AtomicInteger adapterCreations = new AtomicInteger();

        ModelAdapter behavior = messages -> {
            if (isChild(messages)) {
                bothChildrenStarted.countDown();
                try {
                    assertTrue(bothChildrenStarted.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                String prompt = ((minicode.core.message.UserMessage) messages.get(1)).content();
                return new AssistantStep("result for " + prompt, AssistantKind.FINAL);
            }

            boolean delegated = messages.stream().anyMatch(message ->
                    message instanceof AssistantToolCallMessage call && call.toolName().equals("agent"));
            if (!delegated) {
                return new ToolCallsStep(List.of(
                        backgroundExploreCall("agent-call-1", "first area"),
                        backgroundExploreCall("agent-call-2", "second area")),
                        Optional.empty(), ContentKind.UNSPECIFIED, List.of(), Optional.empty(), Optional.empty());
            }

            List<minicode.core.message.UserMessage> notifications = notifications(messages);
            if (notifications.size() < 2) {
                pauseBriefly();
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

        ApplicationServices services = ApplicationServices.createWithModelAdapterFactory(
                home, cwd, "session-a", adapterFactory, AgentEventSink.noOp(),
                PermissionPromptHandler.unavailable());
        try {
            var result = services.runTurn(services.turnRequest(
                    List.of(new minicode.core.message.UserMessage("inspect both areas")), 20));

            assertEquals("summarized two background results",
                    ((AssistantMessage) result.messages().getLast()).content());
            assertEquals(2, notifications(result.messages()).size());
            assertTrue(result.persistencePlan().actions().stream()
                    .filter(minicode.session.plan.PersistenceAction.AppendMessagesAction.class::isInstance)
                    .map(minicode.session.plan.PersistenceAction.AppendMessagesAction.class::cast)
                    .flatMap(action -> action.messages().stream())
                    .noneMatch(message -> message instanceof minicode.core.message.UserMessage user
                            && user.content().contains("<task-notification>")));
            assertEquals(2, services.subAgentTaskManager().orElseThrow().listTasks().size());
            assertTrue(services.subAgentTaskManager().orElseThrow().listTasks().stream()
                    .allMatch(task -> task.status() == AgentTaskStatus.COMPLETED));
            assertEquals(3, adapterCreations.get());
            assertFalse(Files.exists(home.resolve("agent-tasks")));
        } finally {
            services.close();
        }
    }

    private static List<minicode.core.message.UserMessage> notifications(List<ChatMessage> messages) {
        return messages.stream()
                .filter(minicode.core.message.UserMessage.class::isInstance)
                .map(minicode.core.message.UserMessage.class::cast)
                .filter(message -> message.content().contains("<task-notification>"))
                .toList();
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
