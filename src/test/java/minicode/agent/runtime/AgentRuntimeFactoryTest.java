package minicode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentType;
import minicode.agent.event.AgentTaskEvent;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.ChatMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.model.MockModelAdapter;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeFactoryTest {
    @Test
    void builtInSpecsHaveFixedCapabilitiesAndLimits() {
        assertEquals(Set.of(ToolCapability.READ, ToolCapability.COMMAND),
                AgentSpec.forType(AgentType.EXPLORE).allowedCapabilities());
        assertEquals(30, AgentSpec.forType(AgentType.EXPLORE).maxSteps());
        assertEquals(Set.of(ToolCapability.READ, ToolCapability.COMMAND),
                AgentSpec.forType(AgentType.PLAN).allowedCapabilities());
        assertEquals(15, AgentSpec.forType(AgentType.PLAN).maxSteps());
        assertEquals(Set.of(ToolCapability.READ, ToolCapability.WRITE, ToolCapability.COMMAND),
                AgentSpec.forType(AgentType.GENERAL_PURPOSE).allowedCapabilities());
        assertEquals(200, AgentSpec.forType(AgentType.GENERAL_PURPOSE).maxSteps());
        assertTrue(AgentSpec.forType(AgentType.GENERAL_PURPOSE).supports(AgentRunMode.BACKGROUND));
    }

    @Test
    void createsIndependentRuntimeWithOnlySystemAndDelegatedPrompt() {
        ToolRegistry parent = registry();
        List<ToolRegistry> adapterRegistries = new ArrayList<>();
        AgentRuntimeFactory factory = new AgentRuntimeFactory(parent, tools -> {
            adapterRegistries.add(tools);
            return new MockModelAdapter("child answer");
        });
        AgentTaskRequest request = AgentTaskRequest.create(AgentType.EXPLORE, "inspect", "delegated prompt",
                "parent-session", "parent-turn", ".", AgentRunMode.SYNC);

        AgentRuntime first = factory.create(request);
        AgentRuntime second = factory.create(AgentTaskRequest.create(AgentType.EXPLORE, "inspect again", "other",
                "parent-session", "parent-turn", ".", AgentRunMode.SYNC));

        assertNotSame(first.toolRegistry(), second.toolRegistry());
        assertNotSame(first.contextManager(), second.contextManager());
        assertNotSame(first.autoCompactController(), second.autoCompactController());
        assertSame(first.toolRegistry(), adapterRegistries.getFirst());
        assertEquals(List.of("read_file", "run_command", "mcp__server__tool"), first.toolRegistry().list().stream()
                .map(tool -> tool.metadata().name()).toList());
        assertInstanceOf(SystemMessage.class, first.initialMessages().get(0));
        assertEquals("delegated prompt", ((UserMessage) first.initialMessages().get(1)).content());
        assertEquals(2, first.initialMessages().size());
        assertEquals("child answer", first.run().output());
    }

    @Test
    void backgroundUsesTheSameRoleFilterAndAllowsGeneralPurpose() {
        ChildToolRegistryFactory factory = new ChildToolRegistryFactory();
        ToolRegistry background = factory.create(registry(), AgentSpec.forType(AgentType.PLAN),
                AgentRunMode.BACKGROUND);

        assertEquals(List.of("read_file", "run_command", "mcp__server__tool"), background.list().stream()
                .map(tool -> tool.metadata().name()).toList());
        ToolRegistry general = factory.create(registry(), AgentSpec.forType(AgentType.GENERAL_PURPOSE),
                AgentRunMode.BACKGROUND);
        assertEquals(List.of("read_file", "write_file", "run_command", "mcp__server__tool"),
                general.list().stream()
                .map(tool -> tool.metadata().name()).toList());
    }

    @Test
    void generalPurposeKeepsPermissionBearingToolsButNeverDelegationTools() {
        ToolRegistry parent = registry();
        ToolRegistry child = new ChildToolRegistryFactory().create(parent,
                AgentSpec.forType(AgentType.GENERAL_PURPOSE), AgentRunMode.SYNC);

        assertEquals(List.of("read_file", "write_file", "run_command", "mcp__server__tool"),
                child.list().stream().map(tool -> tool.metadata().name()).toList());
        assertSame(parent.find("write_file").orElseThrow(), child.find("write_file").orElseThrow());
        assertSame(parent.find("run_command").orElseThrow(), child.find("run_command").orElseThrow());
        assertSame(parent.find("mcp__server__tool").orElseThrow(),
                child.find("mcp__server__tool").orElseThrow());
    }

    @Test
    void synchronousRunPublishesLifecycleWithoutDurableTaskId() {
        List<AgentTaskEvent> events = new ArrayList<>();
        AgentRuntimeFactory factory = new AgentRuntimeFactory(
                registry(), ignored -> new MockModelAdapter("done"), events::add);

        factory.run(AgentTaskRequest.create(AgentType.EXPLORE, "inspect", "prompt",
                "session", "parent-turn", ".", AgentRunMode.SYNC));

        List<AgentTaskEvent.StateChangedEvent> states = events.stream()
                .filter(AgentTaskEvent.StateChangedEvent.class::isInstance)
                .map(AgentTaskEvent.StateChangedEvent.class::cast)
                .toList();
        assertEquals(List.of(minicode.agent.model.AgentTaskStatus.RUNNING,
                        minicode.agent.model.AgentTaskStatus.COMPLETED),
                states.stream().map(AgentTaskEvent.StateChangedEvent::status).toList());
        assertTrue(states.stream().allMatch(state -> state.taskId().isEmpty()));
    }

    @Test
    void malformedCompletionRetriesAndReturnsOnlyRecoveredFinalAnswer() {
        CountingSequenceAdapter adapter = new CountingSequenceAdapter(
                new AssistantStep(
                        "Still working.\n</parameter>\n</｜DSML｜invoke>",
                        AssistantKind.UNSPECIFIED
                ),
                new AssistantStep("Recovered result", AssistantKind.FINAL)
        );
        AgentRuntimeFactory factory = new AgentRuntimeFactory(registry(), ignored -> adapter);

        var result = factory.run(AgentTaskRequest.create(
                AgentType.EXPLORE, "inspect", "prompt",
                "session", "parent-turn", ".", AgentRunMode.SYNC
        ));

        assertTrue(result.successful());
        assertEquals("Recovered result", result.output());
        assertEquals(2, adapter.calls);
    }

    @Test
    void repeatedMalformedCompletionPublishesFailedInsteadOfCompleted() {
        String looping = "Let me inspect one more thing before finishing.\n".repeat(300);
        CountingSequenceAdapter adapter = new CountingSequenceAdapter(
                new AssistantStep(looping, AssistantKind.UNSPECIFIED)
        );
        List<AgentTaskEvent> events = new ArrayList<>();
        AgentRuntimeFactory factory = new AgentRuntimeFactory(registry(), ignored -> adapter, events::add);

        var result = factory.run(AgentTaskRequest.create(
                AgentType.EXPLORE, "inspect", "prompt",
                "session", "parent-turn", ".", AgentRunMode.SYNC
        ));

        assertFalse(result.successful());
        assertEquals("MODEL_ERROR", result.stopReason());
        assertTrue(result.error().orElseThrow().contains("after 3 attempts"));
        assertEquals(3, adapter.calls);
        assertEquals(List.of(minicode.agent.model.AgentTaskStatus.RUNNING,
                        minicode.agent.model.AgentTaskStatus.FAILED),
                events.stream()
                        .filter(AgentTaskEvent.StateChangedEvent.class::isInstance)
                        .map(AgentTaskEvent.StateChangedEvent.class::cast)
                        .map(AgentTaskEvent.StateChangedEvent::status)
                        .toList());
    }

    private static final class CountingSequenceAdapter implements ModelAdapter {
        private final List<AgentStep> steps;
        private int index;
        private int calls;

        private CountingSequenceAdapter(AgentStep... steps) {
            this.steps = List.of(steps);
        }

        @Override
        public AgentStep next(List<ChatMessage> messages) {
            calls++;
            if (index >= steps.size()) {
                return steps.getLast();
            }
            return steps.get(index++);
        }
    }

    private static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file", ToolOrigin.BUILTIN, ToolCapability.READ));
        registry.register(tool("write_file", ToolOrigin.BUILTIN, ToolCapability.WRITE));
        registry.register(tool("run_command", ToolOrigin.BUILTIN, ToolCapability.COMMAND));
        registry.register(tool("ask_user", ToolOrigin.BUILTIN, ToolCapability.ASK_USER));
        registry.register(tool("agent", ToolOrigin.EXTENSION, ToolCapability.BACKGROUND_TASK));
        registry.register(tool("mcp__server__tool", ToolOrigin.MCP, ToolCapability.COMMAND));
        return registry;
    }

    private static Tool tool(String name, ToolOrigin origin, ToolCapability capability) {
        JsonNode schema = JsonNodeFactory.instance.objectNode();
        ToolMetadata metadata = new ToolMetadata(name, "test tool", schema, origin, Set.of(capability),
                ToolStatus.AVAILABLE);
        return new Tool() {
            @Override public ToolMetadata metadata() { return metadata; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public ValidationResult validateInput(JsonNode input) { return ValidationResult.valid(input); }
            @Override public ToolResult run(JsonNode input, ToolContext context) { return ToolResult.ok("ok"); }
        };
    }
}
