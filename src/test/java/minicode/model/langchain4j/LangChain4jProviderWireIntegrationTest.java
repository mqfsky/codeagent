package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.ToolResultMessage;
import minicode.core.message.UserMessage;
import minicode.core.step.AssistantStep;
import minicode.core.turn.AgentTurnRequest;
import minicode.core.turn.AgentTurnResult;
import minicode.core.turn.AgentTurnStopReason;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 使用真实 LangChain4j Provider 模型与内存 HTTP 客户端执行端到端请求报文回归测试。
 * 测试不会绑定网络端口，也不会调用真实 Provider。
 */
class LangChain4jProviderWireIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = ProviderKind.class, names = {"OPENAI_COMPATIBLE", "ANTHROPIC"})
    void realProviderModelPreservesExactSchemaUrlAuthTimeoutAndMaxTokens(ProviderKind provider)
            throws Exception {
        JsonNode schema = JSON.readTree("""
                {
                  "type": "object",
                  "description": "complex MCP-compatible input",
                  "additionalProperties": false,
                  "properties": {
                    "mode": {
                      "type": "string",
                      "enum": ["safe", "fast"],
                      "default": "safe"
                    },
                    "options": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "depth": {"type": "integer", "default": 2}
                      }
                    },
                    "anything": true
                  },
                  "required": ["mode"]
                }
                """);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new SchemaTool("complex_tool", schema));
        RecordingHttpClientBuilder http =
                new RecordingHttpClientBuilder(providerResponse(provider));
        RuntimeConfig config = config(provider);
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config,
                registry,
                2_345,
                (actualConfig, maxOutputTokens, requestContext) ->
                        new ChatModelFactory().create(
                                actualConfig, maxOutputTokens, requestContext, http),
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());

        AssistantStep step = assertInstanceOf(
                AssistantStep.class,
                adapter.next(List.of(new UserMessage("hello"))));

        assertEquals("done", step.content());
        HttpRequest request = http.client.lastRequest;
        JsonNode body = JSON.readTree(request.body());
        JsonNode actualSchema = provider == ProviderKind.OPENAI_COMPATIBLE
                ? body.at("/tools/0/function/parameters")
                : body.at("/tools/0/input_schema");
        assertEquals(schema, actualSchema);
        assertEquals("safe", actualSchema.at("/properties/mode/default").asText());
        assertEquals(2, actualSchema.at("/properties/options/properties/depth/default").asInt());
        assertFalse(actualSchema.path("additionalProperties").asBoolean(true));
        assertEquals(2_345, body.path("max_tokens").asInt());
        assertEquals(Duration.ofSeconds(7), http.connectTimeout);
        assertEquals(Duration.ofSeconds(7), http.readTimeout);

        if (provider == ProviderKind.OPENAI_COMPATIBLE) {
            assertEquals("https://provider.example/v1/chat/completions", request.url());
            assertEquals(List.of("Bearer provider-key"), header(request, "authorization"));
        } else {
            assertEquals("https://provider.example/v1/messages", request.url());
            assertEquals(List.of("provider-key"), header(request, "x-api-key"));
            assertFalse(hasHeader(request, "authorization"));
        }
    }

    @Test
    void realAnthropicModelRestoresEveryRawThinkingBlockAndToolResult() throws Exception {
        JsonNode schema = JSON.readTree("""
                {
                  "type": "object",
                  "properties": {"mode": {"type": "string"}},
                  "required": ["mode"]
                }
                """);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new SchemaTool("complex_tool", schema));
        RecordingHttpClientBuilder http =
                new RecordingHttpClientBuilder(providerResponse(ProviderKind.ANTHROPIC));
        RuntimeConfig config = config(ProviderKind.ANTHROPIC);
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config,
                registry,
                2_345,
                (actualConfig, maxOutputTokens, requestContext) ->
                        new ChatModelFactory().create(
                                actualConfig, maxOutputTokens, requestContext, http),
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());
        JsonNode thinkingOne = JSON.readTree(
                "{\"type\":\"thinking\",\"thinking\":\"one\",\"signature\":\"sig-1\"}");
        JsonNode redactedOne = JSON.readTree(
                "{\"type\":\"redacted_thinking\",\"data\":\"opaque-1\"}");
        JsonNode thinkingTwo = JSON.readTree(
                "{\"type\":\"thinking\",\"thinking\":\"two\",\"signature\":\"sig-2\"}");
        JsonNode redactedTwo = JSON.readTree(
                "{\"type\":\"redacted_thinking\",\"data\":\"opaque-2\"}");
        List<ChatMessage> history = List.of(
                new UserMessage("first"),
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("thinking", thinkingOne),
                        new ProviderThinkingBlock("redacted_thinking", redactedOne),
                        new ProviderThinkingBlock("thinking", thinkingTwo))),
                new AssistantMessage("first text"),
                new AssistantToolCallMessage(
                        "call-1",
                        "complex_tool",
                        JSON.createObjectNode().put("mode", "safe")),
                new ToolResultMessage("call-1", "complex_tool", "tool failed", true),
                new UserMessage("second"),
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("redacted_thinking", redactedTwo))),
                new AssistantProgressMessage("second progress"),
                new UserMessage("continue"));

        adapter.next(history);

        JsonNode body = JSON.readTree(http.client.lastRequest.body());
        List<JsonNode> assistantMessages = messagesWithRole(body, "assistant");
        assertEquals(2, assistantMessages.size());
        JsonNode firstContent = assistantMessages.get(0).path("content");
        assertEquals(thinkingOne, firstContent.get(0));
        assertEquals(redactedOne, firstContent.get(1));
        assertEquals(thinkingTwo, firstContent.get(2));
        assertEquals("first text", firstContent.get(3).path("text").asText());
        assertEquals("call-1", firstContent.get(4).path("id").asText());
        JsonNode secondContent = assistantMessages.get(1).path("content");
        assertEquals(redactedTwo, secondContent.get(0));
        assertEquals("<progress>\nsecond progress\n</progress>",
                secondContent.get(1).path("text").asText());

        JsonNode toolResult = findContentBlock(body, "tool_result");
        assertEquals("call-1", toolResult.path("tool_use_id").asText());
        assertEquals("tool failed", toolResult.path("content").asText());
        assertEquals(true, toolResult.path("is_error").asBoolean());
    }

    @Test
    void realOpenAiModelCompletesToolResultContinueFinalLoop() throws Exception {
        JsonNode schema = JSON.readTree("""
                {
                  "type": "object",
                  "properties": {"mode": {"type": "string"}},
                  "required": ["mode"]
                }
                """);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new SchemaTool("complex_tool", schema));
        RecordingHttpClientBuilder http = new RecordingHttpClientBuilder(
                """
                        {
                          "id": "chatcmpl-tool",
                          "object": "chat.completion",
                          "created": 1,
                          "model": "test-model",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call-1",
                                "type": "function",
                                "function": {
                                  "name": "complex_tool",
                                  "arguments": "{\\"mode\\":\\"safe\\"}"
                                }
                              }]
                            },
                            "finish_reason": "tool_calls"
                          }],
                          "usage": {
                            "prompt_tokens": 2,
                            "completion_tokens": 1,
                            "total_tokens": 3
                          }
                        }
                        """,
                """
                        {
                          "id": "chatcmpl-final",
                          "object": "chat.completion",
                          "created": 2,
                          "model": "test-model",
                          "choices": [{
                            "index": 0,
                            "message": {
                              "role": "assistant",
                              "content": "<final>openai done</final>"
                            },
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 5,
                            "completion_tokens": 2,
                            "total_tokens": 7
                          }
                        }
                        """);
        RuntimeConfig config = config(ProviderKind.OPENAI_COMPATIBLE);
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config,
                registry,
                2_345,
                (actualConfig, maxOutputTokens, requestContext) ->
                        new ChatModelFactory().create(
                                actualConfig, maxOutputTokens, requestContext, http),
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());
        AgentLoop loop = new AgentLoop(adapter, AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(new AgentTurnRequest(
                "turn-wire",
                Path.of("."),
                "session-wire",
                List.of(new UserMessage("use the tool")),
                4,
                Optional.empty()));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        AssistantMessage finalMessage =
                assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("openai done", finalMessage.content());
        assertEquals(2, http.client.requests.size());
        JsonNode secondBody = JSON.readTree(http.client.requests.get(1).body());
        JsonNode providerToolResult = messagesWithRole(secondBody, "tool").getFirst();
        assertEquals("call-1", providerToolResult.path("tool_call_id").asText());
        assertEquals("unused", providerToolResult.path("content").asText());
    }

    private static RuntimeConfig config(ProviderKind provider) {
        return new RuntimeConfig(
                provider,
                "test-model",
                "https://provider.example/v1/v1/",
                Optional.of("provider-key"),
                Optional.empty(),
                Optional.of(2_345),
                Optional.of(128_000),
                Duration.ofSeconds(7),
                "test");
    }

    private static String providerResponse(ProviderKind provider) {
        if (provider == ProviderKind.ANTHROPIC) {
            return """
                    {
                      "id": "msg-wire",
                      "type": "message",
                      "role": "assistant",
                      "model": "test-model",
                      "content": [{"type": "text", "text": "done"}],
                      "stop_reason": "end_turn",
                      "stop_sequence": null,
                      "usage": {"input_tokens": 1, "output_tokens": 1}
                    }
                    """;
        }
        return """
                {
                  "id": "chatcmpl-wire",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "test-model",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "done"},
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """;
    }

    private static boolean hasHeader(HttpRequest request, String name) {
        return request.headers().keySet().stream()
                .anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private static List<String> header(HttpRequest request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }

    private static List<JsonNode> messagesWithRole(JsonNode body, String role) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            if (role.equals(message.path("role").asText())) {
                result.add(message);
            }
        }
        return List.copyOf(result);
    }

    private static JsonNode findContentBlock(JsonNode body, String type) {
        for (JsonNode message : body.path("messages")) {
            for (JsonNode block : message.path("content")) {
                if (type.equals(block.path("type").asText())) {
                    return block;
                }
            }
        }
        throw new AssertionError("Missing content block: " + type);
    }

    private record SchemaTool(String name, JsonNode schema) implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    name,
                    "schema wire test tool",
                    schema,
                    ToolOrigin.MCP,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE);
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
        public ToolResult run(JsonNode normalizedInput, ToolContext context) {
            return ToolResult.ok("unused");
        }
    }

    private static final class RecordingHttpClientBuilder implements HttpClientBuilder {
        private Duration connectTimeout = Duration.ZERO;
        private Duration readTimeout = Duration.ZERO;
        private final RecordingHttpClient client;

        private RecordingHttpClientBuilder(String... responseBodies) {
            client = new RecordingHttpClient(responseBodies);
        }

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            connectTimeout = timeout;
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            readTimeout = timeout;
            return this;
        }

        @Override
        public HttpClient build() {
            return client;
        }
    }

    private static final class RecordingHttpClient implements HttpClient {
        private final List<String> responseBodies;
        private final List<HttpRequest> requests = new ArrayList<>();
        private int responseIndex;
        private HttpRequest lastRequest;

        private RecordingHttpClient(String... responseBodies) {
            this.responseBodies = List.of(responseBodies);
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            lastRequest = request;
            requests.add(request);
            if (responseIndex >= responseBodies.size()) {
                throw new AssertionError("No configured response for request " + requests.size());
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .headers(Map.of("content-type", List.of("application/json")))
                    .body(responseBodies.get(responseIndex++))
                    .build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            throw new UnsupportedOperationException("streaming is not used");
        }
    }
}
