package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.step.ToolCallsStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatModelFactoryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "https://provider.example, https://provider.example/v1/",
            "https://provider.example/, https://provider.example/v1/",
            "https://provider.example/v1, https://provider.example/v1/",
            "https://provider.example/v1/, https://provider.example/v1/",
            "https://provider.example/v1/v1///, https://provider.example/v1/"
    })
    void normalizesRootAndExistingV1BaseUrls(String configured, String expected) {
        assertEquals(expected, ChatModelFactory.normalizeV1BaseUrl(configured));
    }

    @Test
    void rejectsBlankBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> ChatModelFactory.normalizeV1BaseUrl("  "));
    }

    @Test
    void openAiModelUsesNormalizedUrlBearerHeaderAndConfiguredMaxTokens() throws Exception {
        ProviderRequestContext context = new ProviderRequestContext();
        RecordingHttpClientBuilder recording = new RecordingHttpClientBuilder(openAiResponse());
        RuntimeConfig config = config(
                ProviderKind.OPENAI_COMPATIBLE,
                "https://openai-compatible.example",
                Optional.of("openai-key"),
                Optional.empty());
        ChatModel model = new ChatModelFactory()
                .create(config, 1_234, context, recording);

        invoke(model, context, ProviderKind.OPENAI_COMPATIBLE);

        HttpRequest request = recording.client.lastRequest;
        JsonNode body = MAPPER.readTree(request.body());
        assertEquals("https://openai-compatible.example/v1/chat/completions", request.url());
        assertEquals(List.of("Bearer openai-key"), header(request, "authorization"));
        assertEquals("test-model", body.path("model").asText());
        assertEquals(1_234, body.path("max_tokens").asInt());
        assertEquals(Duration.ofSeconds(5), recording.connectTimeout);
        assertEquals(Duration.ofSeconds(5), recording.readTimeout);
    }

    @Test
    void anthropicModelUsesSingleV1SegmentBearerHeaderAndConfiguredMaxTokens() throws Exception {
        ProviderRequestContext context = new ProviderRequestContext();
        RecordingHttpClientBuilder recording = new RecordingHttpClientBuilder(anthropicResponse());
        RuntimeConfig config = config(
                ProviderKind.ANTHROPIC,
                "https://anthropic-compatible.example/v1/",
                Optional.empty(),
                Optional.of("anthropic-token"));
        ChatModel model = new ChatModelFactory()
                .create(config, 4_321, context, recording);

        invoke(model, context, ProviderKind.ANTHROPIC);

        HttpRequest request = recording.client.lastRequest;
        JsonNode body = MAPPER.readTree(request.body());
        assertEquals("https://anthropic-compatible.example/v1/messages", request.url());
        assertFalse(hasHeader(request, "x-api-key"));
        assertEquals(List.of("Bearer anthropic-token"), header(request, "authorization"));
        assertEquals(List.of("2023-06-01"), header(request, "anthropic-version"));
        assertEquals("test-model", body.path("model").asText());
        assertEquals(4_321, body.path("max_tokens").asInt());
        assertEquals(Duration.ofSeconds(5), recording.connectTimeout);
        assertEquals(Duration.ofSeconds(5), recording.readTimeout);
    }

    @Test
    void anthropicApiKeyUsesOnlyXApiKeyAuthentication() {
        ProviderRequestContext context = new ProviderRequestContext();
        RecordingHttpClientBuilder recording = new RecordingHttpClientBuilder(anthropicResponse());
        RuntimeConfig config = config(
                ProviderKind.ANTHROPIC,
                "https://anthropic-compatible.example",
                Optional.of("anthropic-api-key"),
                Optional.empty());
        ChatModel model = new ChatModelFactory()
                .create(config, 1_024, context, recording);

        invoke(model, context, ProviderKind.ANTHROPIC);

        HttpRequest request = recording.client.lastRequest;
        assertEquals(List.of("anthropic-api-key"), header(request, "x-api-key"));
        assertFalse(hasHeader(request, "authorization"));
    }

    @Test
    void authTokenTakesPrecedenceOverApiKeyForBothProviders() {
        for (ProviderKind provider : List.of(
                ProviderKind.OPENAI_COMPATIBLE, ProviderKind.ANTHROPIC)) {
            ProviderRequestContext context = new ProviderRequestContext();
            RecordingHttpClientBuilder recording = new RecordingHttpClientBuilder(
                    provider == ProviderKind.ANTHROPIC ? anthropicResponse() : openAiResponse());
            RuntimeConfig config = config(
                    provider,
                    "https://provider.example",
                    Optional.of("must-not-be-used"),
                    Optional.of("preferred-token"));
            ChatModel model = new ChatModelFactory()
                    .create(config, 1_024, context, recording);

            invoke(model, context, provider);

            HttpRequest request = recording.client.lastRequest;
            assertEquals(List.of("Bearer preferred-token"), header(request, "authorization"));
            assertFalse(hasHeader(request, "x-api-key"));
        }
    }

    @Test
    void openAiCompatibleAcceptsObjectValuedToolArgumentsBeforeStrictMapping() {
        String response = """
                {
                  "id": "chatcmpl-object-arguments",
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
                          "name": "read_file",
                          "arguments": {"path": "README.md"}
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2
                  }
                }
                """;
        ProviderRequestContext context = new ProviderRequestContext();
        RecordingHttpClientBuilder recording = new RecordingHttpClientBuilder(response);
        RuntimeConfig config = config(
                ProviderKind.OPENAI_COMPATIBLE,
                "https://openai-compatible.example",
                Optional.of("key"),
                Optional.empty());
        ChatModel model = new ChatModelFactory()
                .create(config, 1_024, context, recording);
        ProviderRequestContext.Snapshot snapshot = ProviderRequestContext.snapshot(
                ProviderKind.OPENAI_COMPATIBLE,
                List.of(),
                List.of(new minicode.core.message.UserMessage("read")));

        ChatResponse chatResponse = context.within(
                snapshot,
                () -> model.chat(UserMessage.from("read")));
        ToolCallsStep step = (ToolCallsStep) new ResponseMapper()
                .map(ProviderKind.OPENAI_COMPATIBLE, chatResponse);

        assertEquals(1, step.calls().size());
        assertEquals("call-1", step.calls().getFirst().id());
        assertEquals("read_file", step.calls().getFirst().toolName());
        assertEquals("README.md", step.calls().getFirst().input().path("path").asText());
    }

    private static void invoke(ChatModel model, ProviderRequestContext context,
                               ProviderKind provider) {
        ProviderRequestContext.Snapshot snapshot = ProviderRequestContext.snapshot(
                provider,
                List.of(),
                List.of(new minicode.core.message.UserMessage("hello")));
        context.within(snapshot, () -> model.chat(UserMessage.from("hello")));
    }

    private static RuntimeConfig config(ProviderKind provider,
                                        String baseUrl,
                                        Optional<String> apiKey,
                                        Optional<String> authToken) {
        return new RuntimeConfig(
                provider,
                "test-model",
                baseUrl,
                apiKey,
                authToken,
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(5),
                "test");
    }

    private static String openAiResponse() {
        return """
                {
                  "id": "chatcmpl-test",
                  "object": "chat.completion",
                  "created": 1,
                  "model": "test-model",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "ok"},
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

    private static String anthropicResponse() {
        return """
                {
                  "id": "msg-test",
                  "type": "message",
                  "role": "assistant",
                  "model": "test-model",
                  "content": [{"type": "text", "text": "ok"}],
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {
                    "input_tokens": 1,
                    "output_tokens": 1
                  }
                }
                """;
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

    private static final class RecordingHttpClientBuilder implements HttpClientBuilder {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(10);
        private final RecordingHttpClient client;

        private RecordingHttpClientBuilder(String responseBody) {
            client = new RecordingHttpClient(responseBody);
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
        private final String responseBody;
        private HttpRequest lastRequest;

        private RecordingHttpClient(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            lastRequest = request;
            return SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .headers(Map.of("content-type", List.of("application/json")))
                    .body(responseBody)
                    .build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            lastRequest = request;
        }
    }
}
