package minicode.model.langchain4j;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import minicode.config.ProviderKind;
import minicode.config.RuntimeConfig;

import java.util.Objects;

/** 根据 CodeAgent 原有运行配置创建低层 LangChain4j {@link ChatModel}。 */
final class ChatModelFactory {
    /**
     * LangChain4j 中的 2 表示首次请求失败后最多再重试两次，即一次 Adapter 调用最多三次 HTTP 请求。
     * AgentLoop 原有的外层三次尝试保持不变，所以持续瞬时故障理论上最多产生九次 HTTP 请求。
     *
     * <p>外层 AgentLoop 当前不会依据 {@code retryable} 决定是否再次调用 Adapter；该字段主要用于
     * 最终错误诊断。旧 Anthropic Adapter 对 {@code Retry-After} 的专门解析也已按迁移方案删除，
     * 现在使用 LangChain4j 1.18 自己的退避与抖动策略。</p>
     */
    private static final int MAX_RETRIES = 2;

    /** 生产路径显式使用 JDK HTTP Client，避免依赖运行环境中的 SPI 偶然选择。 */
    ChatModel create(RuntimeConfig config, int maxOutputTokens,
                     ProviderRequestContext requestContext) {
        return create(config, maxOutputTokens, requestContext, new JdkHttpClientBuilder());
    }

    ChatModel create(RuntimeConfig config, int maxOutputTokens,
                     ProviderRequestContext requestContext,
                     HttpClientBuilder httpClientBuilder) {
        RuntimeConfig actualConfig = Objects.requireNonNull(config, "config");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }

        // 保持旧配置优先级：authToken 优先于 apiKey。
        // OpenAI-compatible 会把该值作为 Bearer；Anthropic 的 authToken 由 HTTP 兼容层改写请求头。
        String credential = actualConfig.authToken()
                .or(() -> actualConfig.apiKey())
                .orElseThrow(() -> new IllegalArgumentException("Provider credential is required"));

        // 所有请求都经过兼容装饰器，以便恢复完整 Schema、thinking block 和 Anthropic Bearer 认证。
        ProviderCompatibilityHttpClientBuilder compatibleHttp =
                new ProviderCompatibilityHttpClientBuilder(
                        Objects.requireNonNull(httpClientBuilder, "httpClientBuilder"),
                        Objects.requireNonNull(requestContext, "requestContext"),
                        actualConfig.authToken());

        if (actualConfig.provider() == ProviderKind.OPENAI_COMPATIBLE) {
            // 仅使用低层同步 ChatModel；工具定义由每次 ChatRequest 单独传入。
            return OpenAiChatModel.builder()
                    .httpClientBuilder(compatibleHttp)
                    .baseUrl(normalizeV1BaseUrl(actualConfig.baseUrl()))
                    .apiKey(credential)
                    .modelName(actualConfig.model())
                    .maxTokens(maxOutputTokens)
                    .timeout(actualConfig.providerTimeout())
                    .maxRetries(MAX_RETRIES)
                    .returnThinking(true)
                    .sendThinking(true)
                    .build();
        }
        if (actualConfig.provider() == ProviderKind.ANTHROPIC) {
            // returnThinking/sendThinking 让 LangChain4j 接收 thinking，并允许在历史消息中回放。
            return AnthropicChatModel.builder()
                    .httpClientBuilder(compatibleHttp)
                    .baseUrl(normalizeV1BaseUrl(actualConfig.baseUrl()))
                    .apiKey(credential)
                    .modelName(actualConfig.model())
                    .maxTokens(maxOutputTokens)
                    .timeout(actualConfig.providerTimeout())
                    .maxRetries(MAX_RETRIES)
                    .returnThinking(true)
                    .sendThinking(true)
                    .build();
        }
        throw new IllegalArgumentException("LangChain4j does not support provider: " + actualConfig.provider());
    }

    static String normalizeV1BaseUrl(String configuredBaseUrl) {
        String base = Objects.requireNonNull(configuredBaseUrl, "configuredBaseUrl")
                .trim()
                .replaceAll("/+$", "");
        if (base.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }

        // 先剥离末尾所有重复的 /v1，再统一补回一个 /v1/。
        // 这样无论用户配置根地址、/v1、/v1/ 还是误写多个 /v1，最终路径都保持一致。
        while (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - "/v1".length())
                    .replaceAll("/+$", "");
        }
        if (base.isBlank()) {
            throw new IllegalArgumentException("baseUrl must include a provider origin");
        }
        return base + "/v1/";
    }
}
