package minicode.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.config.RuntimeConfig;
import minicode.core.loop.ModelAdapter;
import minicode.core.message.*;
import minicode.core.step.*;
import minicode.model.ProviderRequestException;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;
import minicode.model.ModelLimits;
import minicode.tools.api.Tool;
import minicode.tools.api.ToolCall;
import minicode.tools.registry.ToolRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public final class OpenAIModelAdapter implements ModelAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BASE_RETRY_DELAY_MS = 500L;
    private static final long MAX_RETRY_DELAY_MS = 8_000L;

    private final RuntimeConfig runtimeConfig;
    private final ToolRegistry tools;
    private final HttpClient httpClient;
    private final int maxRetries;
    private final RetryDelayStrategy retryDelayStrategy;

    public interface RetryDelayStrategy {
        void sleep(long millis);

        static RetryDelayStrategy threadSleep() {
            return millis -> {
                try {
                    Thread.sleep(Math.max(0L, millis));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ProviderRequestException("Provider retry sleep interrupted",
                            Optional.empty(), true, exception);
                }
            };
        }
    }

    public OpenAIModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools) {
        this(runtimeConfig, tools, HttpClient.newHttpClient(), 2, RetryDelayStrategy.threadSleep());
    }

    public OpenAIModelAdapter(RuntimeConfig runtimeConfig, ToolRegistry tools,
                               HttpClient httpClient, int maxRetries, RetryDelayStrategy retryDelayStrategy) {
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.maxRetries = maxRetries;
        this.retryDelayStrategy = Objects.requireNonNull(retryDelayStrategy, "retryDelayStrategy");
    }

    @Override
    public AgentStep next(List<ChatMessage> messages) {
        JsonNode requestBody = buildRequestBody(messages);
        JsonNode responseBody = sendWithRetries(requestBody);
        return parseResponse(responseBody);
    }

    // ===================== 请求体构建 =====================

    private JsonNode buildRequestBody(List<ChatMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", runtimeConfig.model());
        root.set("messages", buildMessages(messages));
        root.set("tools", buildTools());
        int maxTokens = ModelLimits.resolveMaxOutputTokens(runtimeConfig.model(), runtimeConfig.maxOutputTokens());
        root.put("max_tokens", maxTokens);
        root.put("stream", false);
        return root;
    }

    private ArrayNode buildMessages(List<ChatMessage> messages) {
        ArrayNode result = MAPPER.createArrayNode();
        // 遍历历史消息
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                result.add(makeMessage("system", sm.content()));
            } else if (msg instanceof UserMessage um) {
                result.add(makeMessage("user", um.content()));
            } else if (msg instanceof ContextSummaryMessage csm) {
                result.add(makeMessage("user", "[Context Summary from earlier conversation]\n" + csm.content()));
            } else if (msg instanceof AssistantThinkingMessage tm) {
                // thinking blocks → 拼成 assistant message（带 reasoning_content）
                String reasoning = "";
                String text = "";
                for (ProviderThinkingBlock block : tm.blocks()) {
                    // DeepSeek 风格：reasoning_content
                    reasoning += block.raw().path("thinking").asText("")
                            + block.raw().path("reasoning").asText("")
                            + block.raw().path("data").asText("");
                }
                for (JsonNode block : tm.blocks().stream().map(ProviderThinkingBlock::raw).toList()) {
                    text += block.path("text").asText("");
                }
                ObjectNode m = makeMessage("assistant", text);
                if (!reasoning.isBlank()) {
                    m.put("reasoning_content", reasoning.trim());
                }
                result.add(m);
            } else if (msg instanceof AssistantMessage am) {
                result.add(makeMessage("assistant", am.content()));
            } else if (msg instanceof AssistantProgressMessage pm) {
                result.add(makeMessage("assistant", "<progress>\n" + pm.content() + "\n</progress>"));
            } else if (msg instanceof AssistantToolCallMessage tcm) {
                // 工具调用：assistant message with tool_calls
                // 将历史工具调用以及结果回填至 message
                ObjectNode m = MAPPER.createObjectNode();
                m.put("role", "assistant");
                ArrayNode toolCalls = MAPPER.createArrayNode();
                ObjectNode tc = MAPPER.createObjectNode();
                tc.put("id", tcm.toolUseId());
                tc.put("type", "function");
                ObjectNode fn = MAPPER.createObjectNode();
                fn.put("name", tcm.toolName());
                fn.put("arguments", tcm.input().toString());
                tc.set("function", fn);
                toolCalls.add(tc);
                m.set("tool_calls", toolCalls);
                result.add(m);
            } else if (msg instanceof ToolResultMessage trm) {
                ObjectNode m = MAPPER.createObjectNode();
                m.put("role", "tool");
                m.put("tool_call_id", trm.toolUseId());
                m.put("content", trm.content());
                result.add(m);
            }
        }
        return result;
    }

    private ObjectNode makeMessage(String role, String content) {
        ObjectNode m = MAPPER.createObjectNode();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        return m;
    }
    // 格式
//    {
//        "type": "function",
//        "function": {
//                "name": "read_file",
//                "description": "...",
//                "parameters": { "...": "..." }
//        }
//    }
    private ArrayNode buildTools() {
        ArrayNode result = MAPPER.createArrayNode();
        for (Tool tool : tools.list()) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("type", "function");
            ObjectNode fn = MAPPER.createObjectNode();
            fn.put("name", tool.metadata().name());
            fn.put("description", tool.metadata().description());
            fn.set("parameters", tool.inputSchema());
            entry.set("function", fn);
            result.add(entry);
        }
        return result;
    }

    // ===================== HTTP 发送 =====================

    private JsonNode sendWithRetries(JsonNode requestBody) {
        String base = runtimeConfig.baseUrl().replaceAll("/+$", "");
        String url = base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        runtimeConfig.authToken().ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        if (runtimeConfig.authToken().isEmpty()) {
            runtimeConfig.apiKey().ifPresent(k -> headers.put("Authorization", "Bearer " + k));
        }

        ProviderRequestException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(runtimeConfig.providerTimeout())
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()));
                headers.forEach(builder::header);
                HttpResponse<String> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return MAPPER.readTree(response.body());
                }
                if (!shouldRetry(status) || attempt >= maxRetries) {
                    String errMsg = extractErrorMessage(response.body());
                    throw new ProviderRequestException(errMsg, Optional.of(status), shouldRetry(status));
                }
                retryDelayStrategy.sleep(retryDelayMs(attempt + 1));
            } catch (ProviderRequestException e) {
                throw e;
            } catch (Exception exception) {
                lastException = new ProviderRequestException(
                        "Provider request failed: " + exception.getMessage(),
                        Optional.empty(), true, exception);
                if (attempt >= maxRetries) break;
                retryDelayStrategy.sleep(retryDelayMs(attempt + 1));
            }
        }
        if (lastException != null) throw lastException;
        throw new ProviderRequestException("Provider request failed after retries");
    }

    // ===================== 响应解析 =====================

    private AgentStep parseResponse(JsonNode data) {
        JsonNode choice = data.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.isMissingNode() || message.isNull()) {
            throw new ProviderRequestException("No choices in response: " + data);
        }

        String content = message.path("content").asText("");
        String reasoning = message.path("reasoning_content").asText("");
        JsonNode toolCallsNode = message.path("tool_calls");
        List<ProviderThinkingBlock> thinkingBlocks = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<String> blockTypes = new ArrayList<>();
        blockTypes.add("openai_message");

        // 解析 thinking
        if (!reasoning.isBlank()) {
            ObjectNode raw = MAPPER.createObjectNode();
            raw.put("type", "thinking");
            raw.put("thinking", reasoning);
            thinkingBlocks.add(new ProviderThinkingBlock("thinking", raw));
        }

        // 解析工具调用
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            blockTypes.add("tool_calls");
            for (JsonNode tc : toolCallsNode) {
                String id = tc.path("id").asText("");
                String name = tc.path("function").path("name").asText("");
                JsonNode args = tc.path("function").path("arguments");
                // arguments 可能是字符串（JSON）或直接是对象
                JsonNode input;
                if (args.isTextual()) {
                    try {
                        input = MAPPER.readTree(args.asText());
                    } catch (Exception e) {
                        input = MAPPER.createObjectNode();
                    }
                } else if (args.isObject() || args.isArray()) {
                    input = args;
                } else {
                    input = MAPPER.createObjectNode();
                }
                toolCalls.add(new ToolCall(id, name, input));
            }
        }

        String finishReason = choice.path("finish_reason").asText("");
        StepDiagnostics diagnostics = new StepDiagnostics(
                Optional.of(finishReason),
                blockTypes,
                List.of()
        );
        Optional<ProviderUsage> usage = parseUsage(data.path("usage"));

        if (!toolCalls.isEmpty()) {
            return new ToolCallsStep(toolCalls,
                    content.isBlank() ? Optional.empty() : Optional.of(content),
                    ContentKind.UNSPECIFIED,
                    thinkingBlocks,
                    Optional.of(diagnostics),
                    usage);
        }
        ParsedText parsed = parseAssistantText(content);
        return new AssistantStep(parsed.content(), parsed.kind(), thinkingBlocks,
                Optional.of(diagnostics), usage);
    }

    private Optional<ProviderUsage> parseUsage(JsonNode usage) {
        if (usage == null || usage.isNull()) return Optional.empty();
        int input = usage.path("prompt_tokens").asInt(0);
        int output = usage.path("completion_tokens").asInt(0);
        int total = input + output;
        return total <= 0 ? Optional.empty() : Optional.of(new ProviderUsage(input, output, total));
    }

    private ParsedText parseAssistantText(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("<final>")) {
            return new ParsedText(trimmed.substring("<final>".length()).replaceAll("(?i)</final>", "").trim(),
                    AssistantKind.FINAL);
        }
        if (trimmed.startsWith("[FINAL]")) {
            return new ParsedText(trimmed.substring("[FINAL]".length()).trim(), AssistantKind.FINAL);
        }
        if (trimmed.startsWith("<progress>")) {
            return new ParsedText(trimmed.substring("<progress>".length()).replaceAll("(?i)</progress>", "").trim(),
                    AssistantKind.PROGRESS);
        }
        if (trimmed.startsWith("[PROGRESS]")) {
            return new ParsedText(trimmed.substring("[PROGRESS]".length()).trim(), AssistantKind.PROGRESS);
        }
        return new ParsedText(trimmed, AssistantKind.UNSPECIFIED);
    }

    // ===================== 工具方法 =====================

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "Model request failed";
        try {
            JsonNode node = MAPPER.readTree(body);
            return node.path("error").path("message").asText(body);
        } catch (Exception e) {
            return body.trim();
        }
    }

    private boolean shouldRetry(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    private long retryDelayMs(int attempt) {
        long base = Math.min(BASE_RETRY_DELAY_MS * (1L << Math.max(0, Math.min(attempt - 1, 10))),
                MAX_RETRY_DELAY_MS);
        long jitter = Math.floorMod(Objects.hash(runtimeConfig.model(), attempt), Math.max(1L, base / 4L + 1L));
        return Math.min(MAX_RETRY_DELAY_MS, base + jitter);
    }

    /**
     * 从 provider 响应中解析出的文本片段。
     *
     * @param content 解析出的文本内容
     * @param kind 类型枚举
     */
    private record ParsedText(String content, AssistantKind kind) {}
}
