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
                // 一条 thinking 消息可能包含多个 provider 原始 block；这里将它们合并成一条
                // OpenAI 兼容的 assistant 消息，并分别收集内部推理与对用户可见的文本。
                StringBuilder reasoning = new StringBuilder();
                StringBuilder text = new StringBuilder();
                for (ProviderThinkingBlock block : tm.blocks()) {
                    JsonNode raw = block.raw();
                    // 不同 provider 使用的推理字段名不完全一致；字段不存在时按空字符串处理。
                    reasoning.append(raw.path("thinking").asText(""));
                    reasoning.append(raw.path("reasoning").asText(""));
                    reasoning.append(raw.path("data").asText(""));
                    text.append(raw.path("text").asText(""));
                }

                ObjectNode m = makeMessage("assistant", text.toString());
                String reasoningContent = reasoning.toString().trim();
                // reasoning_content 是可选字段，避免没有推理内容时发送无意义的空字段。
                if (!reasoningContent.isBlank()) {
                    m.put("reasoning_content", reasoningContent);
                }
                result.add(m);
                // 最终结构：
                // {
                //  "role": "assistant",
                //  "content": "我先查看相关文件。",
                //  "reasoning_content": "我需要先检查项目结构。"
                // }
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
                // {
                //  "role": "assistant",
                //  "tool_calls": [
                //    {
                //      "id": "call_123",
                //      "type": "function",
                //      "function": {
                //        "name": "read_file",
                //        "arguments": "{\"path\":\"README.md\"}"
                //      }
                //    }
                //  ]
                // }
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

    /**
     * 向 OpenAI-Compatible Chat Completions 接口发送请求，并对临时故障执行退避重试。
     *
     * @param requestBody 已构建完成的 JSON 请求体
     * @return provider 返回并解析后的 JSON 响应
     */
    private JsonNode sendWithRetries(JsonNode requestBody) {
        // 1.拼接请求地址
        // 先去掉 baseUrl 末尾多余的斜杠，再兼容“已包含 /v1”和“未包含 /v1”两种配置。
        String base = runtimeConfig.baseUrl().replaceAll("/+$", "");
        // "https://api.siliconflow.cn/v1/chat/completions",
        String url = base.endsWith("/v1")
                ? base + "/chat/completions"
                : base + "/v1/chat/completions";

        //  2.组装公共请求头：优先使用 authToken，没有时再回退到 apiKey。
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json"); // 告诉 provider：请求体是 json
        runtimeConfig.authToken().ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        if (runtimeConfig.authToken().isEmpty()) {
            runtimeConfig.apiKey().ifPresent(k -> headers.put("Authorization", "Bearer " + k));
        }

        ProviderRequestException lastException = null;
        // maxRetries 表示额外重试次数，因此总请求次数最多为 maxRetries + 1。
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 为本次尝试创建 POST 请求，并使用 provider 级超时限制整个 HTTP 调用。
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)) // 创建 HTTP请求构造器
                        .timeout(runtimeConfig.providerTimeout()) // 单次超时时间
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString())); // 构造 post 请求
                headers.forEach(builder::header); // 请求头逐个放入 HttpRequest.Builder

                // 同步发送请求；响应体先按字符串读取，随后再根据状态码决定如何处理。
                HttpResponse<String> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                // 2xx 表示请求成功，将响应字符串解析成 JsonNode 交给后续响应解析流程。
                if (status >= 200 && status < 300) {
                    return MAPPER.readTree(response.body());
                }

                // 4xx（429 除外）等不可重试状态，或者重试次数已耗尽时，直接抛出 provider 错误。
                // 400：请求参数错误
                // 401：认证失败
                // 403：无权限
                // 404：接口不存在 这些直接失败
                if (!shouldRetry(status) || attempt >= maxRetries) {
                    String errMsg = extractErrorMessage(response.body());
                    throw new ProviderRequestException(errMsg, Optional.of(status), shouldRetry(status));
                }

                // 429 和 5xx 属于临时故障，按退避策略等待后进入下一次尝试。
                retryDelayStrategy.sleep(retryDelayMs(attempt + 1));
            } catch (ProviderRequestException e) {
                // 已经转换好的 provider 异常包含准确的状态和重试标记，保持原样向上抛出。
                throw e;
            } catch (Exception exception) {
                // 网络中断、超时或响应 JSON 解析失败等没有 HTTP 状态码的异常，统一包装后重试。
                lastException = new ProviderRequestException(
                        "Provider request failed: " + exception.getMessage(),
                        Optional.empty(), true, exception);
                if (attempt >= maxRetries) break;
                retryDelayStrategy.sleep(retryDelayMs(attempt + 1));
            }
        }

        // 循环正常结束仅可能来自通用异常重试耗尽，优先抛出最后一次真实异常。
        if (lastException != null) throw lastException;
        throw new ProviderRequestException("Provider request failed after retries");
    }

    // ===================== 响应解析 =====================

    /**
     * 将 OpenAI-Compatible Chat Completions 的成功响应转换为 AgentLoop 使用的统一步骤对象。
     *
     * <p>响应中只要包含有效工具调用就返回 {@link ToolCallsStep}；否则将文本转换为
     * {@link AssistantStep}。两种结果都会尽量保留 thinking、停止原因和 token 用量。</p>
     *
     * @param data provider 返回的完整 JSON 响应
     * @return 工具调用步骤或普通助手文本步骤
     */
    private AgentStep parseResponse(JsonNode data) {
        // OpenAI-Compatible 响应的主要结果位于 choices[0].message；path() 在字段缺失时返回 MissingNode。
        JsonNode choice = data.path("choices").path(0);
        JsonNode message = choice.path("message");
        // choices 为空、第一项没有 message，或 message 显式为 null，都无法继续解析。
        if (message.isMissingNode() || message.isNull()) {
            throw new ProviderRequestException("No choices in response: " + data);
        }

        // 文本、推理内容和工具调用都来自同一个 assistant message；缺失的文本字段按空串处理。
        String content = message.path("content").asText("");
        String reasoning = message.path("reasoning_content").asText("");
        JsonNode toolCallsNode = message.path("tool_calls");

        // 先收集 provider 扩展信息和工具调用，最后再据此决定返回哪一种 AgentStep。
        List<ProviderThinkingBlock> thinkingBlocks = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<String> blockTypes = new ArrayList<>();
        // 当前解析的是一条 OpenAI 风格 message，因此先记录基础 block 类型。
        blockTypes.add("openai_message");

        // 将 provider 特有的 reasoning_content 标准化为项目内部的 thinking block。
        if (!reasoning.isBlank()) {
            ObjectNode raw = MAPPER.createObjectNode();
            raw.put("type", "thinking");
            raw.put("thinking", reasoning);
            thinkingBlocks.add(new ProviderThinkingBlock("thinking", raw));
        }

        // tool_calls 是非空数组时，逐个转换为项目内部的 ToolCall。
        if (toolCallsNode.isArray() && toolCallsNode.size() > 0) {
            // 诊断信息标记本次响应除普通 message 外还包含工具调用块。
            blockTypes.add("tool_calls");
            for (JsonNode tc : toolCallsNode) {
                // OpenAI 工具调用结构：id 标识本次调用，function.name 指定要执行的工具。
                String id = tc.path("id").asText("");
                String name = tc.path("function").path("name").asText("");
                JsonNode args = tc.path("function").path("arguments");

                // 标准 OpenAI 响应通常把 arguments 放在 JSON 字符串里，部分兼容 provider 会直接返回对象或数组。
                JsonNode input;
                if (args.isTextual()) {
                    try {
                        // 对字符串形式的 arguments 再做一次 JSON 解析，得到工具真正接收的结构化参数。
                        input = MAPPER.readTree(args.asText());
                    } catch (Exception e) {
                        // 参数字符串不是合法 JSON 时降级为空对象，避免整个模型响应在此处解析失败。
                        input = MAPPER.createObjectNode();
                    }
                } else if (args.isObject() || args.isArray()) {
                    // provider 已经返回结构化参数时直接复用，不再重复序列化和解析。
                    input = args;
                } else {
                    // arguments 缺失、为 null 或类型不受支持时，统一按空对象处理。
                    input = MAPPER.createObjectNode();
                }
                // ToolCall 构造器会校验 id 和工具名非空，并保存稍后执行工具所需的 input。
                toolCalls.add(new ToolCall(id, name, input));
            }
        }

        // finish_reason 用于诊断模型为何停止；当前实现即使字段缺失也会保存为空字符串。
        String finishReason = choice.path("finish_reason").asText("");
        StepDiagnostics diagnostics = new StepDiagnostics(
                Optional.of(finishReason),
                blockTypes,
                List.of()
        );
        // usage 位于响应根节点，parseUsage 会把 prompt/completion token 转成统一的 ProviderUsage。
        Optional<ProviderUsage> usage = parseUsage(data.path("usage"));

        // 工具调用优先：即使 message 同时携带 content，也要先交给 AgentLoop 执行工具。
        if (!toolCalls.isEmpty()) {
            return new ToolCallsStep(toolCalls,
                    // 工具调用附带的非空文本会一起保留；空文本则表示为 Optional.empty()。
                    content.isBlank() ? Optional.empty() : Optional.of(content),
                    ContentKind.UNSPECIFIED,
                    thinkingBlocks,
                    Optional.of(diagnostics),
                    usage);
        }

        // 没有工具调用时，把 <final>、[FINAL]、<progress> 等标记解析成对应的文本语义类型。
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
        // 429：请求频率过高
        // 500～599：Provider 服务端异常
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
