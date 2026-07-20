package minicode.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.mcp.McpServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuntimeConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    private static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(300);
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private RuntimeConfigLoader() {
    }

    /**
     * 方法调用所需的输入参数集合。
     *
     * @param home CodeAgent 的数据目录
     * @param cwd 当前 workspace 工作目录
     * @param env 环境变量映射
     */
    public record Input(Path home, Path cwd, Map<String, String> env) {
        public Input {
            home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            cwd = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            env = Map.copyOf(Objects.requireNonNull(env, "env"));
        }
    }

    public static RuntimeConfig load(Path home, Path cwd) {
        return load(new Input(home, cwd, System.getenv()));
    }

    public static RuntimeConfig load(Input input) {
        // 校验加载配置所需的输入对象，避免后续访问 home、cwd、env 时出现空指针。
        Objects.requireNonNull(input, "input");

        // 定位用户级配置文件：<home>/settings.json，对所有工作区生效。
        Path homeSettingsPath = input.home().resolve("settings.json");
        // 定位工作区级配置文件：<cwd>/.codeagent/settings.json，只对当前项目生效。
        Path cwdSettingsPath = input.cwd().resolve(".codeagent").resolve("settings.json");

        // 读取用户级配置；文件不存在时得到一个空 JSON 对象。
        JsonNode homeSettings = readSettings(homeSettingsPath);
        // 读取工作区级配置；同名配置会优先于用户级配置。
        JsonNode cwdSettings = readSettings(cwdSettingsPath);

        // 加载模型服务提供商，例如 ANTHROPIC 或 MOCK；未配置时默认使用 ANTHROPIC。
        ProviderKind provider = ProviderKind.parse(firstText(input.env(), homeSettings, cwdSettings,
                "CODEAGENT_PROVIDER", "provider", "ANTHROPIC"));
        // 加载模型名称，依次尝试 CODEAGENT_MODEL、ANTHROPIC_MODEL、model 和 anthropicModel。
        String model = firstNonBlank(
                firstEnvText(input.env(), homeSettings, cwdSettings, "CODEAGENT_MODEL"),
                firstEnvText(input.env(), homeSettings, cwdSettings, "ANTHROPIC_MODEL"),
                firstTopLevelText(homeSettings, cwdSettings, "model"),
                firstTopLevelText(homeSettings, cwdSettings, "anthropicModel")
        );
        // 加载模型服务的基础地址；未配置时使用 Anthropic 官方地址。
        String baseUrl = firstText(input.env(), homeSettings, cwdSettings, "ANTHROPIC_BASE_URL", "baseUrl",
                DEFAULT_ANTHROPIC_BASE_URL);
        // 加载 API Key；空字符串会转换为 Optional.empty()。
        Optional<String> apiKey = optionalText(firstText(input.env(), homeSettings, cwdSettings,
                "ANTHROPIC_API_KEY", "apiKey", ""));
        // 加载认证 Token，作为 API Key 之外的另一种认证方式。
        Optional<String> authToken = optionalText(firstText(input.env(), homeSettings, cwdSettings,
                "ANTHROPIC_AUTH_TOKEN", "authToken", ""));
        // 加载模型单次响应允许生成的最大 Token 数；未配置或不是正整数时保持为空。
        Optional<Integer> maxOutputTokens = positiveInteger(firstText(input.env(), homeSettings, cwdSettings,
                "CODEAGENT_MAX_OUTPUT_TOKENS", "maxOutputTokens", ""));
        // 加载模型上下文窗口大小；未配置或不是正整数时保持为空。
        Optional<Integer> contextWindow = positiveInteger(firstText(input.env(), homeSettings, cwdSettings,
                "CODEAGENT_CONTEXT_WINDOW", "contextWindow", ""));
        // 加载 Agent 单次运行允许执行的最大步骤数，仅从工作区或用户级配置中读取。
        Optional<Integer> maxSteps = positiveInteger(firstTopLevelText(homeSettings, cwdSettings, "maxSteps"));
        // 加载调用模型服务的超时时间（秒）；未配置时使用默认超时时间。
        Duration providerTimeout = providerTimeout(firstText(input.env(), homeSettings, cwdSettings,
                "CODEAGENT_PROVIDER_TIMEOUT_SECONDS", "providerTimeoutSeconds", ""));
        // 合并用户级与工作区级 MCP Server 配置，工作区中的同名配置优先。
        Map<String, McpServerConfig> mcpServers = mcpServers(homeSettings, cwdSettings, input.env());

        // 模型名称是运行必需项，所有来源都没有配置时立即终止启动。
        if (model.isBlank()) {
            throw new RuntimeConfigException("No model configured. Set CODEAGENT_MODEL, ANTHROPIC_MODEL, or home settings.json model.");
        }
        // 除 MOCK 外的真实模型服务必须至少配置 API Key 或认证 Token 中的一种。
        if (provider != ProviderKind.MOCK && apiKey.isEmpty() && authToken.isEmpty()) {
            throw new RuntimeConfigException("No auth configured. Set ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN in env or home settings.json.");
        }

        // 将完成校验和归一化的配置项组装成运行时不可变配置对象。
        return new RuntimeConfig(
                provider,
                model,
                baseUrl,
                apiKey,
                authToken,
                maxOutputTokens,
                contextWindow,
                maxSteps,
                providerTimeout,
                "home=" + input.home() + "; cwd=" + input.cwd()
                        + "; homeSettings=" + homeSettingsPath
                        + "; cwdSettings=" + cwdSettingsPath
                        + "; env"
                ,
                mcpServers
        );
    }

    private static Map<String, McpServerConfig> mcpServers(JsonNode homeSettings, JsonNode cwdSettings,
                                                            Map<String, String> processEnv) {
        Map<String, McpServerConfig> merged = new LinkedHashMap<>();
        mergeMcpServers(merged, homeSettings == null ? null : homeSettings.get("mcpServers"), processEnv);
        mergeMcpServers(merged, cwdSettings == null ? null : cwdSettings.get("mcpServers"), processEnv);
        return Map.copyOf(merged);
    }

    private static void mergeMcpServers(Map<String, McpServerConfig> target, JsonNode servers,
                                        Map<String, String> processEnv) {
        if (servers == null || !servers.isObject()) {
            return;
        }
        servers.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                return;
            }
            McpServerConfig existing = target.get(entry.getKey());
            target.put(entry.getKey(), mergeMcpServer(existing, node, processEnv));
        });
    }

    private static McpServerConfig mergeMcpServer(McpServerConfig existing, JsonNode node,
                                                   Map<String, String> processEnv) {
        String layerCommand = settingsText(node, "command");
        String layerUrl = settingsText(node, "url");
        boolean hasLayerCommand = !layerCommand.isBlank();
        boolean hasLayerUrl = !layerUrl.isBlank();

        String command;
        String url;
        if (hasLayerCommand && !hasLayerUrl) {
            command = layerCommand;
            url = null;
        } else if (hasLayerUrl && !hasLayerCommand) {
            command = "";
            url = layerUrl;
        } else if (hasLayerCommand) {
            command = layerCommand;
            url = layerUrl;
        } else {
            command = existing == null ? "" : existing.command();
            url = existing == null ? null : existing.url().orElse(null);
        }

        boolean usesStdioFields = !command.isBlank();
        boolean usesHttpFields = url != null && !url.isBlank();
        boolean inheritStdioFields = usesStdioFields && existing != null && !existing.command().isBlank();
        boolean inheritHttpFields = usesHttpFields && existing != null && existing.url().isPresent();

        List<String> args = usesStdioFields && node.has("args") && node.get("args").isArray()
                ? stringList(node.get("args"))
                : inheritStdioFields ? existing.args() : List.of();
        Map<String, String> env = new LinkedHashMap<>();
        if (inheritStdioFields) {
            env.putAll(existing.env());
        }
        JsonNode envNode = node.get("env");
        if (usesStdioFields && envNode != null && envNode.isObject()) {
            envNode.fields().forEachRemaining(entry -> env.put(entry.getKey(), entry.getValue().asText("")));
        }
        String cwd = usesStdioFields ? settingsText(node, "cwd") : null;
        if (usesStdioFields && cwd.isBlank() && inheritStdioFields) {
            cwd = existing.cwd().orElse(null);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (inheritHttpFields) {
            headers.putAll(existing.headers());
        }
        JsonNode headersNode = node.get("headers");
        if (usesHttpFields && headersNode != null && headersNode.isObject()) {
            headersNode.fields().forEachRemaining(entry -> putHeader(headers, entry.getKey(),
                    expandEnvironment(entry.getValue().asText(""), processEnv)));
        }

        boolean enabled = node.has("enabled") ? node.get("enabled").asBoolean(true) : existing == null || existing.enabled();
        Duration initializeTimeout = existing == null ? null : existing.initializeTimeout();
        Duration callTimeout = existing == null ? null : existing.callTimeout();
        return new McpServerConfig(command, args, cwd, env, url, headers, enabled,
                initializeTimeout, callTimeout);
    }

    private static String expandEnvironment(String value, Map<String, String> processEnv) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(value);
        StringBuilder expanded = new StringBuilder(value.length());
        while (matcher.find()) {
            String replacement = processEnv.get(matcher.group(1));
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(
                    replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().stream()
                .filter(existingName -> existingName.equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(headers::remove);
        headers.put(name, value);
    }

    private static List<String> stringList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(item -> result.add(item.asText("")));
        return List.copyOf(result);
    }

    private static JsonNode readSettings(Path path) {
        if (!Files.exists(path)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(path.toFile());
        } catch (IOException exception) {
            throw new RuntimeConfigException("Failed to read settings file: " + path, exception);
        }
    }

    private static String firstText(Map<String, String> env, JsonNode homeSettings, JsonNode cwdSettings,
                                    String envName, String settingsName, String fallback) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String cwdEnvValue = settingsEnvText(cwdSettings, envName);
        if (!cwdEnvValue.isBlank()) {
            return cwdEnvValue;
        }
        String homeEnvValue = settingsEnvText(homeSettings, envName);
        if (!homeEnvValue.isBlank()) {
            return homeEnvValue;
        }

        String cwdTopLevelValue = settingsText(cwdSettings, settingsName);
        if (!cwdTopLevelValue.isBlank()) {
            return cwdTopLevelValue;
        }
        String homeTopLevelValue = settingsText(homeSettings, settingsName);
        if (!homeTopLevelValue.isBlank()) {
            return homeTopLevelValue;
        }
        return fallback;
    }

    private static String firstEnvText(Map<String, String> env, JsonNode homeSettings, JsonNode cwdSettings,
                                       String envName) {
        String envValue = env.get(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        String cwdEnvValue = settingsEnvText(cwdSettings, envName);
        if (!cwdEnvValue.isBlank()) {
            return cwdEnvValue;
        }
        return settingsEnvText(homeSettings, envName);
    }

    private static String firstTopLevelText(JsonNode homeSettings, JsonNode cwdSettings, String settingsName) {
        String cwdTopLevelValue = settingsText(cwdSettings, settingsName);
        if (!cwdTopLevelValue.isBlank()) {
            return cwdTopLevelValue;
        }
        return settingsText(homeSettings, settingsName);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String settingsEnvText(JsonNode settings, String envName) {
        JsonNode env = settings == null ? null : settings.get("env");
        if (env == null || !env.isObject()) {
            return "";
        }
        return text(env.get(envName));
    }

    private static String settingsText(JsonNode settings, String settingsName) {
        return text(settings == null ? null : settings.get(settingsName));
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asText("");
        return text.isBlank() ? "" : text.trim();
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static Optional<Integer> positiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static Duration providerTimeout(String value) {
        Optional<Integer> seconds = positiveInteger(value);
        return seconds.map(Duration::ofSeconds).orElse(DEFAULT_PROVIDER_TIMEOUT);
    }
}
