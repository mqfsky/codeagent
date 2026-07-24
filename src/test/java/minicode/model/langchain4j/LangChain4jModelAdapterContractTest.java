package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.SystemMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AgentStep;
import minicode.core.step.ToolCallsStep;
import minicode.model.ProviderRequestException;
import minicode.model.ProviderThinkingBlock;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jModelAdapterContractTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void nextBuildsRequestFromAllMessageKindsAndMapsToolCallsWithoutExecutingTools() {
        CountingTool readFile = new CountingTool("read_file");
        ToolRegistry registry = registry(readFile);
        RecordingModelFactory factory = new RecordingModelFactory(index ->
                new RecordingChatModel(toolCallResponse(
                        "call-new", "read_file", "{\"path\":\"README.md\"}")));
        LangChain4jModelAdapter adapter = adapter(registry, factory);

        ObjectNode thinking = JSON.objectNode()
                .put("type", "thinking")
                .put("thinking", "inspect")
                .put("signature", "signature-1");
        List<ChatMessage> messages = List.of(
                new SystemMessage("system"),
                new UserMessage("start"),
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("thinking", thinking)
                )),
                new AssistantMessage("working"),
                new AssistantProgressMessage("reading files"),
                new AssistantToolCallMessage(
                        "call-old", "read_file", JSON.objectNode().put("path", "pom.xml")),
                new ToolResultMessage("call-old", "read_file", "pom contents", false),
                new ContextSummaryMessage("earlier context", 3, Instant.EPOCH)
        );

        AgentStep result = adapter.next(messages);

        ToolCallsStep step = assertInstanceOf(ToolCallsStep.class, result);
        assertEquals("call-new", step.calls().getFirst().id());
        assertEquals("read_file", step.calls().getFirst().toolName());
        assertEquals("README.md", step.calls().getFirst().input().path("path").asText());

        ChatRequest request = factory.models().getFirst().requests().getFirst();
        assertEquals(List.of("read_file"),
                request.toolSpecifications().stream().map(specification -> specification.name()).toList());
        assertEquals(5, request.messages().size());
        assertEquals("system",
                assertInstanceOf(dev.langchain4j.data.message.SystemMessage.class,
                        request.messages().get(0)).text());
        assertEquals("start",
                assertInstanceOf(dev.langchain4j.data.message.UserMessage.class,
                        request.messages().get(1)).singleText());

        AiMessage assistant = assertInstanceOf(AiMessage.class, request.messages().get(2));
        assertEquals("working\n<progress>\nreading files\n</progress>", assistant.text());
        assertEquals("inspect", assistant.thinking());
        assertEquals("signature-1",
                assistant.attribute(MessageMapper.THINKING_SIGNATURE_KEY, String.class));
        assertEquals("call-old", assistant.toolExecutionRequests().getFirst().id());
        assertEquals("{\"path\":\"pom.xml\"}",
                assistant.toolExecutionRequests().getFirst().arguments());

        ToolExecutionResultMessage toolResult =
                assertInstanceOf(ToolExecutionResultMessage.class, request.messages().get(3));
        assertEquals("call-old", toolResult.id());
        assertEquals("read_file", toolResult.toolName());
        assertEquals("pom contents", toolResult.text());
        assertEquals(Boolean.FALSE, toolResult.isError());
        assertEquals("[Context Summary from earlier conversation]\nearlier context",
                assertInstanceOf(dev.langchain4j.data.message.UserMessage.class,
                        request.messages().get(4)).singleText());
        assertEquals(0, readFile.runCount());
    }

    @Test
    void mapsProviderFailureWithoutExecutingTools() {
        CountingTool tool = new CountingTool("read_file");
        RecordingModelFactory factory = new RecordingModelFactory(index ->
                new RecordingChatModel(new RetriableException(
                        "rate limited", new HttpException(429, "too many requests"))));
        LangChain4jModelAdapter adapter = adapter(registry(tool), factory);

        ProviderRequestException exception = assertThrows(
                ProviderRequestException.class,
                () -> adapter.next(List.of(new UserMessage("retry")))
        );

        assertEquals(Optional.of(429), exception.statusCode());
        assertTrue(exception.retryable());
        assertEquals("rate limited", exception.getMessage());
        assertEquals(1, factory.models().getFirst().requests().size());
        assertEquals(0, tool.runCount());
    }

    @Test
    void forkCreatesIndependentChatModelBoundToItsOwnRegistry() {
        CountingTool parentTool = new CountingTool("parent_tool");
        CountingTool childTool = new CountingTool("child_tool");
        ToolRegistry parentRegistry = registry(parentTool);
        ToolRegistry childRegistry = registry(childTool);
        RecordingModelFactory factory = new RecordingModelFactory(index ->
                new RecordingChatModel(finalResponse("model-" + index)));
        LangChain4jModelAdapter parent = adapter(parentRegistry, factory);

        ModelAdapter child = parent.fork(childRegistry);
        AgentStep parentResult = parent.next(List.of(new UserMessage("parent")));
        AgentStep childResult = child.next(List.of(new UserMessage("child")));

        assertEquals(2, factory.models().size());
        assertNotSame(factory.models().get(0), factory.models().get(1));
        assertEquals(List.of("parent_tool"), toolNames(factory.models().get(0).requests().getFirst()));
        assertEquals(List.of("child_tool"), toolNames(factory.models().get(1).requests().getFirst()));
        assertEquals("model-0",
                assertInstanceOf(minicode.core.step.AssistantStep.class, parentResult).content());
        assertEquals("model-1",
                assertInstanceOf(minicode.core.step.AssistantStep.class, childResult).content());
        assertEquals(0, parentTool.runCount());
        assertEquals(0, childTool.runCount());
    }

    @Test
    void nextReadsTheRegistryAgainForEveryRequest() {
        CountingTool first = new CountingTool("first_tool");
        CountingTool second = new CountingTool("second_tool");
        ToolRegistry registry = registry(first);
        RecordingModelFactory factory = new RecordingModelFactory(index ->
                new RecordingChatModel(finalResponse("ok")));
        LangChain4jModelAdapter adapter = adapter(registry, factory);

        adapter.next(List.of(new UserMessage("first request")));
        registry.register(second);
        adapter.next(List.of(new UserMessage("second request")));

        List<ChatRequest> requests = factory.models().getFirst().requests();
        assertEquals(List.of("first_tool"), toolNames(requests.get(0)));
        assertEquals(List.of("first_tool", "second_tool"), toolNames(requests.get(1)));
        assertEquals(0, first.runCount());
        assertEquals(0, second.runCount());
    }

    private static LangChain4jModelAdapter adapter(ToolRegistry registry,
                                                    RecordingModelFactory factory) {
        return new LangChain4jModelAdapter(
                config(),
                registry,
                512,
                factory,
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper()
        );
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig(
                ProviderKind.OPENAI_COMPATIBLE,
                "test-model",
                "https://example.invalid/v1",
                Optional.of("test-key"),
                Optional.empty(),
                Optional.of(512),
                Optional.of(8_192),
                "test"
        );
    }

    private static ToolRegistry registry(CountingTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (CountingTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static List<String> toolNames(ChatRequest request) {
        return request.toolSpecifications().stream()
                .map(specification -> specification.name())
                .toList();
    }

    private static ChatResponse toolCallResponse(String id, String name, String arguments) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(arguments)
                        .build()))
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();
    }

    private static ChatResponse finalResponse(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static final class RecordingModelFactory implements LangChain4jModelAdapter.ModelFactory {
        private final IntFunction<RecordingChatModel> modelFactory;
        private final List<RecordingChatModel> models = new ArrayList<>();

        private RecordingModelFactory(IntFunction<RecordingChatModel> modelFactory) {
            this.modelFactory = modelFactory;
        }

        @Override
        public ChatModel create(RuntimeConfig config, int maxOutputTokens,
                                ProviderRequestContext requestContext) {
            RecordingChatModel model = modelFactory.apply(models.size());
            models.add(model);
            return model;
        }

        private List<RecordingChatModel> models() {
            return List.copyOf(models);
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final ChatResponse response;
        private final RuntimeException failure;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(ChatResponse response) {
            this.response = response;
            this.failure = null;
        }

        private RecordingChatModel(RuntimeException failure) {
            this.response = null;
            this.failure = failure;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        private List<ChatRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private static final class CountingTool implements Tool {
        private final ToolMetadata metadata;
        private final JsonNode inputSchema;
        private final AtomicInteger runCount = new AtomicInteger();

        private CountingTool(String name) {
            this.inputSchema = JSON.objectNode()
                    .put("type", "object")
                    .set("properties", JSON.objectNode()
                            .set("value", JSON.objectNode().put("type", "string")));
            this.metadata = new ToolMetadata(
                    name,
                    "test tool " + name,
                    inputSchema,
                    ToolOrigin.BUILTIN,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE
            );
        }

        @Override
        public ToolMetadata metadata() {
            return metadata;
        }

        @Override
        public JsonNode inputSchema() {
            return inputSchema;
        }

        @Override
        public ValidationResult validateInput(JsonNode input) {
            return ValidationResult.valid(input);
        }

        @Override
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            runCount.incrementAndGet();
            return ToolResult.ok("executed");
        }

        private int runCount() {
            return runCount.get();
        }
    }
}
