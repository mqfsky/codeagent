package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import minicode.config.ProviderKind;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.ChatMessage;
import minicode.core.message.UserMessage;
import minicode.model.ProviderThinkingBlock;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolContext;
import minicode.tools.api.ValidationResult;
import minicode.tools.metadata.ToolCapability;
import minicode.tools.metadata.ToolMetadata;
import minicode.tools.metadata.ToolOrigin;
import minicode.tools.metadata.ToolStatus;
import minicode.tools.result.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCompatibilityHttpClientBuilderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void restoresOriginalOpenAiToolSchemaByToolName() throws Exception {
        JsonNode schema = complexSchema();
        HttpRequest captured = execute(
                ProviderKind.OPENAI_COMPATIBLE,
                List.of(new SchemaTool("complex_tool", schema)),
                List.of(),
                Optional.empty(),
                """
                        {
                          "tools": [{
                            "type": "function",
                            "function": {
                              "name": "complex_tool",
                              "parameters": {"type": "object"}
                            }
                          }]
                        }
                        """,
                Map.of());

        JsonNode restored = MAPPER.readTree(captured.body())
                .path("tools").path(0).path("function").path("parameters");

        assertEquals(schema, restored);
        assertFalse(restored.path("additionalProperties").asBoolean(true));
        assertEquals("safe", restored.path("properties").path("mode").path("default").asText());
        assertEquals(2, restored.path("properties").path("options")
                .path("properties").path("depth").path("default").asInt());
    }

    @Test
    void restoresOriginalAnthropicToolSchemaByToolName() throws Exception {
        JsonNode schema = complexSchema();
        HttpRequest captured = execute(
                ProviderKind.ANTHROPIC,
                List.of(new SchemaTool("complex_tool", schema)),
                List.of(),
                Optional.empty(),
                """
                        {
                          "tools": [{
                            "name": "complex_tool",
                            "input_schema": {"type": "object"}
                          }]
                        }
                        """,
                Map.of());

        JsonNode restored = MAPPER.readTree(captured.body())
                .path("tools").path(0).path("input_schema");

        assertEquals(schema, restored);
        assertFalse(restored.path("additionalProperties").asBoolean(true));
        assertEquals("safe", restored.path("properties").path("mode").path("default").asText());
        assertEquals(2, restored.path("properties").path("options")
                .path("properties").path("depth").path("default").asInt());
    }

    @Test
    void anthropicBearerAuthenticationRemovesApiKeyAndReplacesAuthorization() {
        HttpRequest captured = execute(
                ProviderKind.ANTHROPIC,
                List.of(),
                List.of(),
                Optional.of("auth-token"),
                "{}",
                Map.of(
                        "x-api-key", List.of("must-not-leak"),
                        "AUTHORIZATION", List.of("Basic stale"),
                        "anthropic-version", List.of("2023-06-01")));

        assertFalse(hasHeader(captured, "x-api-key"));
        assertEquals(List.of("Bearer auth-token"), header(captured, "authorization"));
        assertEquals(List.of("2023-06-01"), header(captured, "anthropic-version"));
    }

    @Test
    void restoresMultipleThinkingAndRedactedBlocksForEveryAssistantTurn() throws Exception {
        JsonNode thinkingOne = MAPPER.readTree(
                """
                        {"type":"thinking","thinking":"first","signature":"sig-1"}
                        """);
        JsonNode redactedOne = MAPPER.readTree(
                """
                        {"type":"redacted_thinking","data":"redacted-1"}
                        """);
        JsonNode thinkingTwo = MAPPER.readTree(
                """
                        {"type":"thinking","thinking":"second","signature":"sig-2"}
                        """);
        JsonNode redactedTwo = MAPPER.readTree(
                """
                        {"type":"redacted_thinking","data":"redacted-2"}
                        """);
        List<ChatMessage> history = List.of(
                new UserMessage("first user turn"),
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("thinking", thinkingOne),
                        new ProviderThinkingBlock("redacted_thinking", redactedOne),
                        new ProviderThinkingBlock("thinking", thinkingTwo))),
                new AssistantMessage("first answer"),
                new UserMessage("second user turn"),
                new AssistantThinkingMessage(List.of(
                        new ProviderThinkingBlock("redacted_thinking", redactedTwo))),
                new AssistantMessage("second answer"));

        HttpRequest captured = execute(
                ProviderKind.ANTHROPIC,
                List.of(),
                history,
                Optional.empty(),
                """
                        {
                          "messages": [
                            {"role":"user","content":"first user turn"},
                            {"role":"assistant","content":[{"type":"text","text":"flattened-1"}]},
                            {"role":"user","content":"second user turn"},
                            {"role":"assistant","content":[{"type":"text","text":"flattened-2"}]}
                          ]
                        }
                        """,
                Map.of());

        ArrayNode messages = (ArrayNode) MAPPER.readTree(captured.body()).path("messages");
        ArrayNode firstContent = (ArrayNode) messages.path(1).path("content");
        ArrayNode secondContent = (ArrayNode) messages.path(3).path("content");

        assertEquals(List.of(thinkingOne, redactedOne, thinkingTwo),
                List.of(firstContent.get(0), firstContent.get(1), firstContent.get(2)));
        assertEquals("first answer", firstContent.path(3).path("text").asText());
        assertEquals(redactedTwo, secondContent.get(0));
        assertEquals("second answer", secondContent.path(1).path("text").asText());
    }

    @Test
    void withinRestoresPreviousContextAndDoesNotLeakAfterFailure() {
        ProviderRequestContext context = new ProviderRequestContext();
        ProviderRequestContext.Snapshot outer = ProviderRequestContext.snapshot(
                ProviderKind.OPENAI_COMPATIBLE, List.of(), List.of());
        ProviderRequestContext.Snapshot inner = ProviderRequestContext.snapshot(
                ProviderKind.ANTHROPIC, List.of(), List.of());

        context.within(outer, () -> {
            assertSame(outer, context.current());
            assertThrows(IllegalStateException.class, () ->
                    context.within(inner, () -> {
                        assertSame(inner, context.current());
                        throw new IllegalStateException("boom");
                    }));
            assertSame(outer, context.current());
            return null;
        });

        assertNull(context.current());
    }

    private static HttpRequest execute(ProviderKind provider,
                                       List<Tool> tools,
                                       List<ChatMessage> messages,
                                       Optional<String> anthropicAuthToken,
                                       String body,
                                       Map<String, List<String>> headers) {
        ProviderRequestContext context = new ProviderRequestContext();
        ProviderRequestContext.Snapshot snapshot =
                ProviderRequestContext.snapshot(provider, tools, messages);
        RecordingHttpClientBuilder recording = new RecordingHttpClientBuilder();
        HttpClient client = new ProviderCompatibilityHttpClientBuilder(
                recording, context, anthropicAuthToken).build();
        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://provider.example/v1/messages")
                .headers(headers)
                .body(body)
                .build();

        context.within(snapshot, () -> client.execute(request));

        assertTrue(recording.client.lastRequest != null, "delegate should receive a request");
        return recording.client.lastRequest;
    }

    private static JsonNode complexSchema() throws Exception {
        return MAPPER.readTree(
                """
                        {
                          "type": "object",
                          "additionalProperties": false,
                          "properties": {
                            "mode": {
                              "type": "string",
                              "default": "safe"
                            },
                            "options": {
                              "type": "object",
                              "additionalProperties": false,
                              "properties": {
                                "depth": {
                                  "type": "integer",
                                  "default": 2
                                }
                              }
                            }
                          }
                        }
                        """);
    }

    private static boolean hasHeader(HttpRequest request, String name) {
        return request.headers().keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    private static List<String> header(HttpRequest request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }

    private record SchemaTool(String name, JsonNode schema) implements Tool {
        @Override
        public ToolMetadata metadata() {
            return new ToolMetadata(
                    name,
                    "schema test tool",
                    schema,
                    ToolOrigin.BUILTIN,
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
        public ToolResult run(JsonNode normalizedInput, ToolContext toolContext) {
            return ToolResult.ok("unused");
        }
    }

    private static final class RecordingHttpClientBuilder implements HttpClientBuilder {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(10);
        private final RecordingHttpClient client = new RecordingHttpClient();

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
        private HttpRequest lastRequest;

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            lastRequest = request;
            return SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .body("{}")
                    .build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            lastRequest = request;
        }
    }
}
