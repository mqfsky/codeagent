package minicode.config;

import minicode.mcp.McpServerConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 模型 provider 和运行参数的配置快照。
 *
 * @param provider 模型 provider 类型
 * @param model 模型名称
 * @param baseUrl provider API 基础地址
 * @param apiKey API key；为空表示未配置或由其他认证方式提供
 * @param authToken 认证 token；为空表示未配置
 * @param maxOutputTokens 配置的最大输出 token；为空表示按模型默认值解析
 * @param contextWindow 配置的模型上下文窗口；为空表示按模型默认值解析
 * @param maxSteps 单轮 turn 的最大 step 数；为空表示使用 TUI 默认值
 * @param providerTimeout provider 请求超时时间
 * @param sourceSummary 配置来源摘要
 * @param mcpServers MCP server 配置列表
 */
public record RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                            Optional<String> authToken, Optional<Integer> maxOutputTokens,
                            Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                            Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers,
                            IntegrationsConfig integrations) {
    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, String sourceSummary) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                Duration.ofSeconds(300), sourceSummary, Map.of(), IntegrationsConfig.empty());
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Duration providerTimeout, String sourceSummary) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                providerTimeout, sourceSummary, Map.of(), IntegrationsConfig.empty());
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, String sourceSummary,
                         Map<String, McpServerConfig> mcpServers) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, Optional.empty(),
                Duration.ofSeconds(300), sourceSummary, mcpServers, IntegrationsConfig.empty());
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                         Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers) {
        this(provider, model, baseUrl, apiKey, authToken, maxOutputTokens, contextWindow, maxSteps,
                providerTimeout, sourceSummary, mcpServers, IntegrationsConfig.empty());
    }

    public RuntimeConfig(ProviderKind provider, String model, String baseUrl, Optional<String> apiKey,
                         Optional<String> authToken, Optional<Integer> maxOutputTokens,
                         Optional<Integer> contextWindow, Optional<Integer> maxSteps,
                         Duration providerTimeout, String sourceSummary, Map<String, McpServerConfig> mcpServers,
                         IntegrationsConfig integrations) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = requireText(model, "model");
        this.baseUrl = requireText(baseUrl, "baseUrl");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.authToken = Objects.requireNonNull(authToken, "authToken");
        this.maxOutputTokens = Objects.requireNonNull(maxOutputTokens, "maxOutputTokens");
        this.contextWindow = Objects.requireNonNull(contextWindow, "contextWindow");
        this.maxSteps = requirePositiveOptional(maxSteps, "maxSteps");
        this.providerTimeout = requirePositive(providerTimeout, "providerTimeout");
        this.sourceSummary = requireText(sourceSummary, "sourceSummary");
        this.mcpServers = Map.copyOf(Objects.requireNonNull(mcpServers, "mcpServers"));
        this.integrations = Objects.requireNonNull(integrations, "integrations");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Duration actual = Objects.requireNonNull(value, name);
        if (actual.isZero() || actual.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return actual;
    }

    private static Optional<Integer> requirePositiveOptional(Optional<Integer> value, String name) {
        Optional<Integer> actual = Objects.requireNonNull(value, name);
        actual.ifPresent(number -> {
            if (number <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        });
        return actual;
    }
}
