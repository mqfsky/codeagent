package minicode.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;
import minicode.model.ModelMetadata;
import minicode.model.ProviderRequestException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicModelsApiClientTest {
    @Test
    void parsesModelMetadataResponse() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"id":"claude-opus-4-7","max_input_tokens":200000,"max_tokens":64000,"capabilities":{}}
                """);
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(config(), transport);

        Optional<ModelMetadata> metadata = client.fetch("claude-opus-4-7");

        assertTrue(metadata.isPresent());
        assertEquals("claude-opus-4-7", metadata.orElseThrow().id());
        assertEquals(Optional.of(200_000L), metadata.orElseThrow().maxInputTokens());
        assertEquals(Optional.of(64_000), metadata.orElseThrow().maxOutputTokens());
        assertEquals("https://example.test/v1/models/claude-opus-4-7", transport.url);
        assertTrue(transport.headers.containsKey("Authorization"));
    }

    @Test
    void doesNotDuplicateV1WhenBaseUrlAlreadyContainsIt() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"id":"deepseek-ai/DeepSeek-V4-Flash","max_input_tokens":1048576}
                """);
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(
                config("https://api.siliconflow.cn/v1/"), transport);

        Optional<ModelMetadata> metadata = client.fetch("deepseek-ai/DeepSeek-V4-Flash");

        assertTrue(metadata.isPresent());
        assertEquals("https://api.siliconflow.cn/v1/models/deepseek-ai/DeepSeek-V4-Flash", transport.url);
    }

    @Test
    void openAiCompatibleApiKeyUsesBearerWithoutAnthropicHeaders() {
        RecordingTransport transport = metadataTransport();
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(
                config(ProviderKind.OPENAI_COMPATIBLE, Optional.of("openai-key"), Optional.empty()), transport);

        assertTrue(client.fetch("deepseek-ai/DeepSeek-V4-Flash").isPresent());
        assertEquals(Map.of("Authorization", "Bearer openai-key"), transport.headers);
    }

    @Test
    void anthropicApiKeyUsesApiKeyAndVersionHeaders() {
        RecordingTransport transport = metadataTransport();
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(
                config(ProviderKind.ANTHROPIC, Optional.of("anthropic-key"), Optional.empty()), transport);

        assertTrue(client.fetch("claude-opus-4-7").isPresent());
        assertEquals("anthropic-key", transport.headers.get("x-api-key"));
        assertEquals("2023-06-01", transport.headers.get("anthropic-version"));
        assertFalse(transport.headers.containsKey("Authorization"));
    }

    @Test
    void openAiCompatibleAuthTokenTakesPrecedenceOverApiKey() {
        RecordingTransport transport = metadataTransport();
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(
                config(ProviderKind.OPENAI_COMPATIBLE, Optional.of("openai-key"), Optional.of("auth-token")),
                transport);

        assertTrue(client.fetch("deepseek-ai/DeepSeek-V4-Flash").isPresent());
        assertEquals(Map.of("Authorization", "Bearer auth-token"), transport.headers);
    }

    @Test
    void returnsEmptyWhenEndpointDoesNotSupportModelsApi() {
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(config(), new RecordingTransport(404, "{}"));

        assertTrue(client.fetch("mimo-v2.5-pro").isEmpty());
    }

    @Test
    void returnsEmptyWhenFieldsAreMissing() {
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(config(), new RecordingTransport(200, "{}"));

        assertTrue(client.fetch("unknown").isEmpty());
    }

    @Test
    void returnsEmptyWhenTransportFails() {
        AnthropicModelsApiClient client = new AnthropicModelsApiClient(config(), new FailingTransport());

        assertTrue(client.fetch("mimo-v2.5-pro").isEmpty());
    }

    private static RuntimeConfig config() {
        return config("https://example.test");
    }

    private static RuntimeConfig config(String baseUrl) {
        return config(ProviderKind.ANTHROPIC, baseUrl, Optional.empty(), Optional.of("secret-token"));
    }

    private static RuntimeConfig config(ProviderKind provider, Optional<String> apiKey,
                                        Optional<String> authToken) {
        return config(provider, "https://example.test", apiKey, authToken);
    }

    private static RuntimeConfig config(ProviderKind provider, String baseUrl, Optional<String> apiKey,
                                        Optional<String> authToken) {
        return new RuntimeConfig(
                provider,
                "claude-opus-4-7",
                baseUrl,
                apiKey,
                authToken,
                Optional.empty(),
                Optional.empty(),
                Duration.ofSeconds(30),
                "test"
        );
    }

    private static RecordingTransport metadataTransport() {
        return new RecordingTransport(200, """
                {"id":"test-model","max_input_tokens":128000,"max_tokens":8192}
                """);
    }

    private static final class RecordingTransport implements AnthropicTransport {
        private final int status;
        private final String body;
        private String url;
        private Map<String, String> headers;

        private RecordingTransport(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Response post(String url, Map<String, String> headers, JsonNode requestBody) {
            throw new UnsupportedOperationException("models API uses get");
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = Map.copyOf(headers);
            return new Response(status, Map.of(), body);
        }
    }

    private static final class FailingTransport implements AnthropicTransport {
        @Override
        public Response post(String url, Map<String, String> headers, JsonNode requestBody) {
            throw new UnsupportedOperationException("models API uses get");
        }

        @Override
        public Response get(String url, Map<String, String> headers) {
            throw new ProviderRequestException("network unavailable");
        }
    }
}
