package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentType;
import minicode.agent.runtime.AgentSpec;
import minicode.agent.runtime.ChildToolRegistryFactory;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.UserMessage;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.registry.ToolRegistry;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderToolIsolationTest {
    @ParameterizedTest
    @EnumSource(value = ProviderKind.class, names = {"ANTHROPIC", "OPENAI_COMPATIBLE"})
    void forkBuildsEachRequestFromItsOwnFilteredRegistry(ProviderKind provider) {
        ToolRegistry parent = new ToolRegistry();
        parent.register(tool("read_file", ToolOrigin.BUILTIN, ToolCapability.READ));
        parent.register(tool("write_file", ToolOrigin.BUILTIN, ToolCapability.WRITE));
        parent.register(tool("run_command", ToolOrigin.BUILTIN, ToolCapability.COMMAND));
        parent.register(tool("ask_user", ToolOrigin.BUILTIN, ToolCapability.ASK_USER));
        parent.register(tool("agent", ToolOrigin.BUILTIN, ToolCapability.BACKGROUND_TASK));
        parent.register(tool("mcp__server__tool", ToolOrigin.MCP, ToolCapability.COMMAND));
        ToolRegistry child = new ChildToolRegistryFactory().create(
                parent, AgentSpec.forType(AgentType.EXPLORE), AgentRunMode.BACKGROUND);

        List<List<String>> requestToolNames = new ArrayList<>();
        LangChain4jModelAdapter.ModelFactory recordingFactory =
                (config, maxOutputTokens, requestContext) -> recordingModel(requestToolNames);
        LangChain4jModelAdapter parentAdapter = new LangChain4jModelAdapter(
                config(provider),
                parent,
                4_096,
                recordingFactory,
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());
        ModelAdapter childAdapter = parentAdapter.fork(child);

        parentAdapter.next(List.of(new UserMessage("inspect parent")));
        childAdapter.next(List.of(new UserMessage("inspect child")));

        assertEquals(List.of(
                        "read_file", "write_file", "run_command",
                        "ask_user", "agent", "mcp__server__tool"),
                requestToolNames.get(0));
        assertEquals(List.of("read_file", "run_command", "mcp__server__tool"),
                requestToolNames.get(1));

        ToolResult blockedExecution = child.execute(
                new ToolCall("write", "write_file", JsonNodeFactory.instance.objectNode()),
                new ToolContext(Path.of("."), "session", Optional.of("turn"), Optional.of("write")));
        assertTrue(blockedExecution.error());
        assertEquals("Unknown tool: write_file", blockedExecution.content());
    }

    private static ChatModel recordingModel(List<List<String>> requestToolNames) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                requestToolNames.add(request.toolSpecifications().stream()
                        .map(specification -> specification.name())
                        .toList());
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from("done"))
                        .build();
            }
        };
    }

    private static RuntimeConfig config(ProviderKind provider) {
        return new RuntimeConfig(provider, "test-model", "https://provider.example", Optional.of("key"),
                Optional.empty(), Optional.of(4_096), Optional.of(128_000), "test");
    }

    private static Tool tool(String name, ToolOrigin origin, ToolCapability capability) {
        JsonNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        ToolMetadata metadata = new ToolMetadata(name, "test", schema, origin, Set.of(capability),
                ToolStatus.AVAILABLE);
        return new Tool() {
            @Override
            public ToolMetadata metadata() {
                return metadata;
            }

            @Override
            public JsonNode inputSchema() {
                return schema;
            }

            @Override
            public ValidationResult validateInput(JsonNode input) {
                return ValidationResult.valid(input);
            }

            @Override
            public ToolResult run(JsonNode input, ToolContext context) {
                return ToolResult.ok("ok");
            }
        };
    }
}
