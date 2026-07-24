package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.context.accounting.TokenAccountingService;
import minicode.context.stats.ContextStatsCalculator;
import minicode.context.stats.ModelContextWindow;
import minicode.core.event.AgentEvent;
import minicode.core.event.AgentEventSink;
import minicode.core.loop.AgentLoop;
import minicode.core.message.*;
import minicode.core.turn.*;
import minicode.model.ModelRequestException;
import minicode.model.ProviderUsage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.service.SessionService;
import minicode.session.store.SessionStore;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LangChain4jAgentLoopSemanticsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void fixtureRunsToolUseToolResultContinueFinalWithUsageAndContextStats() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceHttpClientBuilder http = new SequenceHttpClientBuilder(
                ok("""
                        {
                          "id": "msg-tool",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "tool_use",
                          "stop_sequence": null,
                          "content": [
                            {"type": "text", "text": "<progress>checking echo</progress>"},
                            {"type": "tool_use", "id": "tool-1", "name": "echo_fixture", "input": {"text": "hello"}}
                          ],
                          "usage": {"input_tokens": 10, "output_tokens": 5}
                        }
                        """),
                ok("""
                        {
                          "id": "msg-final",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "end_turn",
                          "stop_sequence": null,
                          "content": [
                            {"type": "text", "text": "<final>echo complete</final>"}
                          ],
                          "usage": {"input_tokens": 30, "output_tokens": 7}
                        }
                        """)
        );
        RecordingEventSink eventSink = new RecordingEventSink();
        AgentLoop loop = new AgentLoop(
                adapter(registry, http),
                eventSink,
                registry,
                minicode.context.manager.ContextManager.noOp(),
                new ContextStatsCalculator(new TokenAccountingService(), new ModelContextWindow(160, 40))
        );

        AgentTurnResult result = loop.runTurn(request(List.of(
                new SystemMessage("sys"),
                new UserMessage("use tool"))));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(2, http.calls);
        assertInstanceOf(AssistantProgressMessage.class, result.messages().get(2));
        assertInstanceOf(UserMessage.class, result.messages().get(3));
        AssistantToolCallMessage toolCall =
                assertInstanceOf(AssistantToolCallMessage.class, result.messages().get(4));
        assertEquals(new ProviderUsage(10, 5, 15), toolCall.providerUsage().orElseThrow());
        ToolResultMessage toolResult =
                assertInstanceOf(ToolResultMessage.class, result.messages().get(5));
        assertEquals("echo: hello", toolResult.content());
        AssistantMessage finalMessage =
                assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("echo complete", finalMessage.content());
        assertEquals(new ProviderUsage(30, 7, 37), finalMessage.providerUsage().orElseThrow());

        List<AgentEvent.ContextStatsEvent> statsEvents = eventSink.events.stream()
                .filter(AgentEvent.ContextStatsEvent.class::isInstance)
                .map(AgentEvent.ContextStatsEvent.class::cast)
                .toList();
        assertEquals(2, statsEvents.size());
        assertEquals(160, statsEvents.getFirst().stats().contextWindow());
        assertEquals(40, statsEvents.getFirst().stats().outputReserve());
        assertEquals(120, statsEvents.getFirst().stats().effectiveInput());
    }

    @Test
    void fixtureGroupsMultipleToolUsesThenMultipleToolResultsForProviderRequest() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceHttpClientBuilder http = new SequenceHttpClientBuilder(
                ok("""
                        {
                          "id": "msg-tools",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "tool_use",
                          "stop_sequence": null,
                          "content": [
                            {"type": "tool_use", "id": "tool-1", "name": "echo_fixture", "input": {"text": "one"}},
                            {"type": "tool_use", "id": "tool-2", "name": "echo_fixture", "input": {"text": "two"}}
                          ],
                          "usage": {"input_tokens": 8, "output_tokens": 4}
                        }
                        """),
                ok("""
                        {
                          "id": "msg-final",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "end_turn",
                          "stop_sequence": null,
                          "content": [
                            {"type": "text", "text": "<final>both complete</final>"}
                          ],
                          "usage": {"input_tokens": 20, "output_tokens": 5}
                        }
                        """)
        );
        AgentLoop loop = new AgentLoop(adapter(registry, http), AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("use two tools"))));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertMultiToolRequestShape(http.requestBodies.get(1).path("messages"));
    }

    @Test
    void fixturePreservesMultiToolHistoryShapeAcrossSessionResumeRoundTrip() {
        SessionStore store = new SessionStore(tempDir);
        SessionPersistenceRunner runner = new SessionPersistenceRunner(
                store,
                new SessionEventFactory("session-1", "E:/work"));
        runner.apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendMessagesAction(List.of(
                        new UserMessage("use two tools"),
                        new AssistantToolCallMessage(
                                "tool-1",
                                "echo_fixture",
                                JsonNodeFactory.instance.objectNode().put("text", "one"),
                                Optional.of(new ProviderUsage(8, 4, 12)),
                                minicode.model.UsageStaleness.fresh()),
                        new AssistantToolCallMessage(
                                "tool-2",
                                "echo_fixture",
                                JsonNodeFactory.instance.objectNode().put("text", "two")),
                        new ToolResultMessage("tool-1", "echo_fixture", "echo: one", false),
                        new ToolResultMessage("tool-2", "echo_fixture", "echo: two", false)
                ))
        )));
        List<ChatMessage> resumedMessages =
                new SessionService(store).resumeMessages("E:/work", "session-1");
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceHttpClientBuilder http = new SequenceHttpClientBuilder(ok("""
                {
                  "id": "msg-resumed",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-test",
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "content": [
                    {"type": "text", "text": "<final>resumed</final>"}
                  ],
                  "usage": {"input_tokens": 1, "output_tokens": 1}
                }
                """));

        adapter(registry, http).next(resumedMessages);

        assertMultiToolRequestShape(http.requestBodies.getFirst().path("messages"));
    }

    @Test
    void fixtureUsesAgentLoopEmptyResponseFallbackAfterRecentToolError() {
        ToolRegistry registry = registry(new EchoTool(true));
        SequenceHttpClientBuilder http = new SequenceHttpClientBuilder(
                ok("""
                        {
                          "id": "msg-tool",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "tool_use",
                          "stop_sequence": null,
                          "content": [
                            {"type": "tool_use", "id": "tool-1", "name": "echo_fixture", "input": {"text": "fail"}}
                          ],
                          "usage": {"input_tokens": 3, "output_tokens": 2}
                        }
                        """),
                emptyResponse("msg-empty-1"),
                emptyResponse("msg-empty-2"),
                emptyResponse("msg-empty-3")
        );
        AgentLoop loop = new AgentLoop(adapter(registry, http), AgentEventSink.noOp(), registry);

        AgentTurnResult result =
                loop.runTurn(request(List.of(new UserMessage("use failing tool"))));

        assertEquals(AgentTurnStopReason.EMPTY_RESPONSE_FALLBACK, result.stopReason());
        assertEquals(4, http.calls);
        EmptyFallbackDetails details =
                assertInstanceOf(EmptyFallbackDetails.class, result.stopDetails().orElseThrow());
        assertEquals(Optional.of("empty_after_tool_error"), details.reason());
        assertEquals(1, details.toolErrorCount());
        assertTrue(result.messages().stream().anyMatch(message ->
                message instanceof UserMessage user
                        && user.content().contains("recent tool results that included errors")));
        AssistantMessage fallback =
                assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertTrue(fallback.content().contains("tool error"));
    }

    @Test
    void fixtureRecoversPauseTurnAndMaxTokensThinkingStops() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceHttpClientBuilder http = new SequenceHttpClientBuilder(
                ok("""
                        {
                          "id": "msg-pause",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "pause_turn",
                          "stop_sequence": null,
                          "content": [
                            {"type": "thinking", "thinking": "need more time", "signature": "sig-1"}
                          ],
                          "usage": {"input_tokens": 1, "output_tokens": 1}
                        }
                        """),
                ok("""
                        {
                          "id": "msg-max",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "max_tokens",
                          "stop_sequence": null,
                          "content": [
                            {"type": "redacted_thinking", "data": "opaque"}
                          ],
                          "usage": {"input_tokens": 2, "output_tokens": 2}
                        }
                        """),
                ok("""
                        {
                          "id": "msg-final",
                          "type": "message",
                          "role": "assistant",
                          "model": "claude-test",
                          "stop_reason": "end_turn",
                          "stop_sequence": null,
                          "content": [
                            {"type": "text", "text": "<final>recovered</final>"}
                          ],
                          "usage": {"input_tokens": 12, "output_tokens": 4}
                        }
                        """)
        );
        AgentLoop loop = new AgentLoop(adapter(registry, http), AgentEventSink.noOp(), registry);

        AgentTurnResult result =
                loop.runTurn(request(List.of(new UserMessage("think then finish"))));

        assertEquals(AgentTurnStopReason.FINAL, result.stopReason());
        assertEquals(3, http.calls);
        List<AssistantProgressMessage> progressMessages = result.messages().stream()
                .filter(AssistantProgressMessage.class::isInstance)
                .map(AssistantProgressMessage.class::cast)
                .toList();
        assertEquals(2, progressMessages.size());
        assertTrue(progressMessages.get(0).content().contains("pause_turn"));
        assertTrue(progressMessages.get(1).content().contains("max_tokens"));
        List<UserMessage> continuationPrompts = result.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .filter(message -> message.content().contains("Resume")
                        || message.content().contains("hit max_tokens"))
                .toList();
        assertEquals(2, continuationPrompts.size());
        AssistantMessage finalMessage =
                assertInstanceOf(AssistantMessage.class, result.messages().getLast());
        assertEquals("recovered", finalMessage.content());
    }

    @Test
    void fixtureReturnsProviderNeutralModelErrorWithoutAssistantFinal() {
        ToolRegistry registry = registry(new EchoTool(false));
        SequenceHttpClientBuilder http =
                new SequenceHttpClientBuilder(error(429, "rate limited"));
        AgentLoop loop = new AgentLoop(adapter(registry, http), AgentEventSink.noOp(), registry);

        AgentTurnResult result = loop.runTurn(request(List.of(new UserMessage("hi"))));

        assertEquals(AgentTurnStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(3, http.calls);
        assertEquals(1, result.messages().size());
        assertFalse(result.messages().stream().anyMatch(AssistantMessage.class::isInstance));
        ModelErrorDetails details =
                assertInstanceOf(ModelErrorDetails.class, result.stopDetails().orElseThrow());
        assertTrue(details.error().retryable());
        assertEquals(TurnErrorSource.MODEL, details.error().source());
        assertEquals(Optional.of(ModelRequestException.class.getName()), details.error().causeClass());
        assertTrue(details.error().diagnostics().orElseThrow().contains("statusCode=429"));
    }

    private static LangChain4jModelAdapter adapter(ToolRegistry registry,
                                                   SequenceHttpClientBuilder http) {
        return new LangChain4jModelAdapter(
                config(),
                registry,
                1_024,
                (runtimeConfig, maxOutputTokens, requestContext) -> {
                    String credential = runtimeConfig.authToken()
                            .or(() -> runtimeConfig.apiKey())
                            .orElseThrow();
                    return AnthropicChatModel.builder()
                            .httpClientBuilder(new ProviderCompatibilityHttpClientBuilder(
                                    http,
                                    requestContext,
                                    runtimeConfig.authToken()))
                            .baseUrl(ChatModelFactory.normalizeV1BaseUrl(
                                    runtimeConfig.baseUrl()))
                            .apiKey(credential)
                            .modelName(runtimeConfig.model())
                            .maxTokens(maxOutputTokens)
                            .timeout(runtimeConfig.providerTimeout())
                            .maxRetries(0)
                            .returnThinking(true)
                            .sendThinking(true)
                            .build();
                },
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());
    }

    private static void assertMultiToolRequestShape(JsonNode providerMessages) {
        assertEquals("user", providerMessages.get(0).path("role").asText());
        assertEquals("assistant", providerMessages.get(1).path("role").asText());
        assertEquals("tool_use",
                providerMessages.get(1).path("content").get(0).path("type").asText());
        assertEquals("tool_use",
                providerMessages.get(1).path("content").get(1).path("type").asText());
        assertEquals("tool-1",
                providerMessages.get(1).path("content").get(0).path("id").asText());
        assertEquals("tool-2",
                providerMessages.get(1).path("content").get(1).path("id").asText());
        assertEquals("user", providerMessages.get(2).path("role").asText());
        assertEquals("tool_result",
                providerMessages.get(2).path("content").get(0).path("type").asText());
        assertEquals("tool_result",
                providerMessages.get(2).path("content").get(1).path("type").asText());
        assertEquals("tool-1",
                providerMessages.get(2).path("content").get(0).path("tool_use_id").asText());
        assertEquals("tool-2",
                providerMessages.get(2).path("content").get(1).path("tool_use_id").asText());
    }

    private static StubResponse ok(String body) {
        return new StubResponse(200, body, "");
    }

    private static StubResponse error(int statusCode, String message) {
        return new StubResponse(
                statusCode,
                "{\"error\":{\"message\":\"" + message + "\"}}",
                message);
    }

    private static StubResponse emptyResponse(String id) {
        return ok("""
                {
                  "id": "%s",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-test",
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "content": [],
                  "usage": {"input_tokens": 0, "output_tokens": 0}
                }
                """.formatted(id));
    }

    private static RuntimeConfig config() {
        return new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                "claude-test",
                "https://anthropic.example",
                Optional.of("test-key"),
                Optional.empty(),
                Optional.of(1_024),
                Optional.of(160),
                "test"
        );
    }

    private static AgentTurnRequest request(List<ChatMessage> messages) {
        return new AgentTurnRequest(
                "turn-1",
                Path.of("E:/Minicode-Java/workspace"),
                "session-1",
                messages,
                8,
                Optional.empty()
        );
    }

    private static ToolRegistry registry(Tool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private static final class EchoTool implements Tool {
        private final boolean fail;

        private EchoTool(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    "echo_fixture",
                    "Echoes the text field. Test fixture for provider/agent loop semantics.",
                    inputSchema(),
                    ToolOrigin.BUILTIN,
                    Set.of(ToolCapability.READ),
                    ToolStatus.AVAILABLE
            );
        }

        @Override
        public JsonNode inputSchema() {
            return JsonNodeFactory.instance.objectNode()
                    .put("type", "object")
                    .set("properties", JsonNodeFactory.instance.objectNode()
                            .set("text", JsonNodeFactory.instance.objectNode()
                                    .put("type", "string")));
        }

        @Override
        public ValidationResult validateInput(JsonNode input) {
            return ValidationResult.valid(input == null || input.isMissingNode()
                    ? JsonNodeFactory.instance.objectNode()
                    : input);
        }

        @Override
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            if (fail) {
                return ToolResult.error("fixture failure");
            }
            return ToolResult.ok("echo: " + normalizedInput.path("text").asText(""));
        }
    }

    private static final class SequenceHttpClientBuilder implements HttpClientBuilder {
        private final List<StubResponse> responses;
        private final List<JsonNode> requestBodies = new ArrayList<>();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(10);
        private int calls;

        private SequenceHttpClientBuilder(StubResponse... responses) {
            if (responses.length == 0) {
                throw new IllegalArgumentException("At least one response is required");
            }
            this.responses = List.of(responses);
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
            return new SequenceHttpClient();
        }

        private final class SequenceHttpClient implements HttpClient {
            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                StubResponse response;
                synchronized (SequenceHttpClientBuilder.this) {
                    try {
                        requestBodies.add(MAPPER.readTree(request.body()).deepCopy());
                    } catch (Exception exception) {
                        throw new IllegalStateException("Expected a JSON request body", exception);
                    }
                    response = responses.get(Math.min(calls, responses.size() - 1));
                    calls++;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new HttpException(response.statusCode(), response.errorMessage());
                }
                return SuccessfulHttpResponse.builder()
                        .statusCode(response.statusCode())
                        .headers(Map.of("content-type", List.of("application/json")))
                        .body(response.body())
                        .build();
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser,
                                ServerSentEventListener listener) {
                throw new UnsupportedOperationException("Streaming is not used by this fixture");
            }
        }
    }

    private record StubResponse(int statusCode, String body, String errorMessage) {
    }

    private static final class RecordingEventSink implements AgentEventSink {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void onEvent(AgentEvent event) {
            events.add(event);
        }
    }
}
