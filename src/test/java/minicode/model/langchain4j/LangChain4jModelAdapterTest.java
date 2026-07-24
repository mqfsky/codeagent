package minicode.model.langchain4j;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.core.message.UserMessage;
import minicode.core.step.AssistantStep;
import minicode.model.ProviderRequestException;
import minicode.tools.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jModelAdapterTest {
    @Test
    void requestMaxTokensUsesModelDefaultWhenUnconfigured() throws Exception {
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config("claude-3-5-haiku-latest", Optional.empty()),
                new ToolRegistry(),
                Optional.empty());

        assertEquals(8_192, configuredChatModelMaxTokens(adapter));
    }

    @Test
    void requestMaxTokensClampsConfiguredValueToModelLimit() throws Exception {
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config("claude-3-5-haiku-latest", Optional.of(100_000)),
                new ToolRegistry(),
                Optional.empty());

        assertEquals(8_192, configuredChatModelMaxTokens(adapter));
    }

    @Test
    void requestMaxTokensCanUseResolvedProfileValueFromMetadata() throws Exception {
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config("custom-metadata-model", Optional.empty()),
                new ToolRegistry(),
                Optional.of(64_000));

        assertEquals(64_000, configuredChatModelMaxTokens(adapter));
    }

    @Test
    void providerErrorIsNormalizedByAdapter() {
        ChatModel failingModel = new ChatModel() {
            @Override
            public dev.langchain4j.model.chat.response.ChatResponse doChat(ChatRequest request) {
                throw new RateLimitException(
                        "rate limited",
                        new HttpException(429, "{\"error\":{\"message\":\"rate limited\"}}"));
            }
        };
        LangChain4jModelAdapter adapter = adapterWithModel(failingModel);

        ProviderRequestException exception = assertThrows(
                ProviderRequestException.class,
                () -> adapter.next(List.of(new UserMessage("hi"))));

        assertEquals(429, exception.statusCode().orElseThrow());
        assertTrue(exception.getMessage().contains("rate limited"));
        assertTrue(exception.retryable());
    }

    @Test
    void retryableProviderErrorIsRetriedBeforeReturningStep() {
        RetryThenSuccessHttpClientBuilder httpClient =
                new RetryThenSuccessHttpClientBuilder(1, anthropicResponse());
        RuntimeConfig config = config("claude-test", Optional.of(4_096));
        LangChain4jModelAdapter.ModelFactory modelFactory =
                (actualConfig, maxOutputTokens, requestContext) ->
                        new ChatModelFactory().create(
                                actualConfig, maxOutputTokens, requestContext, httpClient);
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config,
                new ToolRegistry(),
                4_096,
                modelFactory,
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());

        AssistantStep step = assertInstanceOf(
                AssistantStep.class,
                adapter.next(List.of(new UserMessage("hi"))));

        assertEquals("ok", step.content());
        assertEquals(2, httpClient.client.calls);
    }

    @Test
    void maxRetriesTwoMeansAtMostThreeProviderAttempts() {
        RetryThenSuccessHttpClientBuilder httpClient =
                new RetryThenSuccessHttpClientBuilder(3, anthropicResponse());
        RuntimeConfig config = config("claude-test", Optional.of(4_096));
        LangChain4jModelAdapter.ModelFactory modelFactory =
                (actualConfig, maxOutputTokens, requestContext) ->
                        new ChatModelFactory().create(
                                actualConfig, maxOutputTokens, requestContext, httpClient);
        LangChain4jModelAdapter adapter = new LangChain4jModelAdapter(
                config,
                new ToolRegistry(),
                4_096,
                modelFactory,
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());

        ProviderRequestException exception = assertThrows(
                ProviderRequestException.class,
                () -> adapter.next(List.of(new UserMessage("hi"))));

        assertEquals(3, httpClient.client.calls);
        assertEquals(Optional.of(429), exception.statusCode());
        assertTrue(exception.retryable());
    }

    private static LangChain4jModelAdapter adapterWithModel(ChatModel model) {
        return new LangChain4jModelAdapter(
                config("claude-test", Optional.of(4_096)),
                new ToolRegistry(),
                4_096,
                (config, maxOutputTokens, requestContext) -> model,
                new MessageMapper(),
                new ToolSpecificationMapper(),
                new ResponseMapper(),
                new ProviderExceptionMapper());
    }

    private static int configuredChatModelMaxTokens(LangChain4jModelAdapter adapter)
            throws ReflectiveOperationException {
        Field chatModelField = LangChain4jModelAdapter.class.getDeclaredField("chatModel");
        chatModelField.setAccessible(true);
        ChatModel chatModel = (ChatModel) chatModelField.get(adapter);
        return chatModel.defaultRequestParameters().maxOutputTokens();
    }

    private static RuntimeConfig config(String model, Optional<Integer> maxOutputTokens) {
        return new RuntimeConfig(
                ProviderKind.ANTHROPIC,
                model,
                "https://anthropic.example",
                Optional.of("key"),
                Optional.empty(),
                maxOutputTokens,
                Optional.of(200_000),
                Duration.ofSeconds(5),
                "test");
    }

    private static String anthropicResponse() {
        return """
                {
                  "id": "msg-test",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-test",
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

    private static final class RetryThenSuccessHttpClientBuilder implements HttpClientBuilder {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(10);
        private final RetryThenSuccessHttpClient client;

        private RetryThenSuccessHttpClientBuilder(int failuresBeforeSuccess, String responseBody) {
            client = new RetryThenSuccessHttpClient(failuresBeforeSuccess, responseBody);
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

    private static final class RetryThenSuccessHttpClient implements HttpClient {
        private final int failuresBeforeSuccess;
        private final String responseBody;
        private int calls;

        private RetryThenSuccessHttpClient(int failuresBeforeSuccess, String responseBody) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.responseBody = responseBody;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            calls++;
            if (calls <= failuresBeforeSuccess) {
                throw new HttpException(429, "{\"error\":{\"message\":\"rate limited\"}}");
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .headers(Map.of("content-type", List.of("application/json")))
                    .body(responseBody)
                    .build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            throw new UnsupportedOperationException("streaming is not used by this adapter");
        }
    }
}
