package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.FormDataFile;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import minicode.config.ProviderKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 HTTP 报文边界恢复 LangChain4j 通用模型无法完整表达的 CodeAgent Provider 数据。
 *
 * <p>这个装饰器不改变 Runtime，也不执行工具，只在请求发出前修复认证头、工具 Schema
 * 和 Anthropic 历史 content，并在响应交给 LangChain4j 前兼容少量旧 Provider 格式。</p>
 */
final class ProviderCompatibilityHttpClientBuilder implements HttpClientBuilder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClientBuilder delegate;
    private final ProviderRequestContext requestContext;
    private final Optional<String> anthropicAuthToken;

    ProviderCompatibilityHttpClientBuilder(HttpClientBuilder delegate,
                                           ProviderRequestContext requestContext,
                                           Optional<String> anthropicAuthToken) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.anthropicAuthToken = Objects.requireNonNull(anthropicAuthToken, "anthropicAuthToken");
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public ProviderCompatibilityHttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public ProviderCompatibilityHttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        // 保留调用方提供的底层 HTTP Client，只在外面包一层请求/响应转换。
        return new CompatibilityHttpClient(delegate.build());
    }

    private final class CompatibilityHttpClient implements HttpClient {
        private final HttpClient delegateClient;

        private CompatibilityHttpClient(HttpClient delegateClient) {
            this.delegateClient = Objects.requireNonNull(delegateClient, "delegateClient");
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            // 同步 ChatModel 走这里：先修复最终请求报文，再规范化成功响应中的兼容字段。
            SuccessfulHttpResponse response = delegateClient.execute(transform(request));
            return normalizeResponse(response);
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            delegateClient.execute(transform(request), parser, listener);
        }
    }

    private HttpRequest transform(HttpRequest request) {
        ProviderRequestContext.Snapshot snapshot = requestContext.current();

        // HttpRequest 的 headers 可能是不可变 Map，先复制后再做大小写不敏感的认证头修改。
        Map<String, List<String>> headers = mutableHeaders(request.headers());

        if (snapshot != null && snapshot.provider() == ProviderKind.ANTHROPIC
                && anthropicAuthToken.isPresent()) {
            // AnthropicChatModel 会自动添加 x-api-key。authToken 模式必须删掉它，
            // 并清理可能已有的 Authorization，确保线上只发送一个 Bearer 凭证。
            removeHeader(headers, "x-api-key");
            removeHeader(headers, "authorization");
            headers.put("Authorization", List.of("Bearer " + anthropicAuthToken.orElseThrow()));
        }

        String body = request.body();
        if (snapshot != null && body != null && !body.isBlank()) {
            // ThreadLocal 快照只在当前同步调用及 LangChain4j 内部重试期间可见。
            body = patchBody(body, snapshot);
        }
        return rebuild(request, headers, body);
    }

    /**
     * 兼容部分 OpenAI-compatible 网关直接返回 JSON object 形式的
     * {@code function.arguments}。
     *
     * <p>标准 OpenAI 协议要求它是“包含 JSON 的字符串”，而 LangChain4j 会在我们的响应
     * 映射器之前按字符串反序列化。旧 Adapter 接受 object 形态，因此这里仅把合法 object
     * 编码成等价字符串；数组、数字、坏 JSON 等其他形态保持原样，让后续严格校验继续失败，
     * 不能静默兜底成空对象。</p>
     */
    private SuccessfulHttpResponse normalizeResponse(SuccessfulHttpResponse response) {
        ProviderRequestContext.Snapshot snapshot = requestContext.current();

        // 该兼容仅属于 OpenAI-compatible 响应；Anthropic 原始响应必须完全保留，
        // 因为 ResponseMapper 还要从 raw body 读取 stop_reason 和 thinking block。
        if (snapshot == null || snapshot.provider() != ProviderKind.OPENAI_COMPATIBLE) {
            return response;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return response;
        }

        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)
                    || !normalizeOpenAiToolArguments(root)) {
                // 没有 object 形态 arguments 时直接返回原响应，避免无意义地重写 JSON。
                return response;
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(response.statusCode())
                    .headers(response.headers())
                    .body(MAPPER.writeValueAsString(root))
                    .build();
        } catch (Exception ignored) {
            // 兼容解析失败时保留 Provider 原始响应，让 LangChain4j 报出正常的协议解析错误。
            return response;
        }
    }

    private boolean normalizeOpenAiToolArguments(ObjectNode root) {
        JsonNode choicesNode = root.get("choices");
        if (!(choicesNode instanceof ArrayNode choices)) {
            return false;
        }

        boolean changed = false;
        for (JsonNode choiceNode : choices) {
            JsonNode toolCallsNode = choiceNode.path("message").get("tool_calls");
            if (!(toolCallsNode instanceof ArrayNode toolCalls)) {
                continue;
            }
            for (JsonNode toolCallNode : toolCalls) {
                JsonNode functionNode = toolCallNode.get("function");
                if (!(functionNode instanceof ObjectNode function)) {
                    continue;
                }
                JsonNode arguments = function.get("arguments");
                if (arguments != null && arguments.isObject()) {
                    // 只转换 object；非 object 仍会在 LangChain4j 或 ResponseMapper 中被拒绝。
                    function.put("arguments", arguments.toString());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private String patchBody(String body, ProviderRequestContext.Snapshot snapshot) {
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                // 无法确认报文结构时禁止继续发送，避免兼容层修复了一半的数据。
                throw new NonRetriableException("Provider request body must be a JSON object");
            }

            // 顺序很重要：先恢复两种 Provider 都需要的工具 Schema，再处理 Anthropic 专属历史。
            restoreToolSchemas(root, snapshot);
            if (snapshot.provider() == ProviderKind.ANTHROPIC) {
                restoreAnthropicAssistantContent(root, snapshot);
            }
            return MAPPER.writeValueAsString(root);
        } catch (NonRetriableException exception) {
            // 已经明确分类为不可重试的本地协议错误，不再重复包装。
            throw exception;
        } catch (Exception exception) {
            // 兼容层自身失败不是 Provider 瞬时故障，重试同一份坏报文没有意义。
            throw new NonRetriableException("Failed to preserve provider request compatibility", exception);
        }
    }

    private void restoreToolSchemas(ObjectNode root, ProviderRequestContext.Snapshot snapshot) {
        if (snapshot.toolSchemas().isEmpty()) {
            return;
        }
        JsonNode toolsNode = root.get("tools");
        if (!(toolsNode instanceof ArrayNode tools)) {
            throw new NonRetriableException("LangChain4j omitted configured tool schemas");
        }

        // 不能依赖数组下标，因为 Provider 集成可能调整工具序列化细节；按稳定的工具名配对。
        Map<String, Boolean> restored = new LinkedHashMap<>();
        for (JsonNode toolNode : tools) {
            if (!(toolNode instanceof ObjectNode tool)) {
                continue;
            }
            if (snapshot.provider() == ProviderKind.OPENAI_COMPATIBLE) {
                JsonNode functionNode = tool.get("function");
                if (functionNode instanceof ObjectNode function) {
                    String name = function.path("name").asText("");
                    JsonNode schema = snapshot.toolSchemas().get(name);
                    if (schema != null) {
                        // 覆盖 parameters，恢复 default、枚举、嵌套约束、additionalProperties 等全部字段。
                        function.set("parameters", schema.deepCopy());
                        restored.put(name, true);
                    }
                }
            } else {
                String name = tool.path("name").asText("");
                JsonNode schema = snapshot.toolSchemas().get(name);
                if (schema != null) {
                    // Anthropic 对应字段名是 input_schema，内容仍使用同一份原始 Schema。
                    tool.set("input_schema", schema.deepCopy());
                    restored.put(name, true);
                }
            }
        }

        List<String> missing = snapshot.toolSchemas().keySet().stream()
                .filter(name -> !restored.containsKey(name))
                .toList();
        if (!missing.isEmpty()) {
            // 只要有一个工具没被恢复，就不能发送“模型可见工具”与 Runtime 不一致的请求。
            throw new NonRetriableException("LangChain4j omitted tool schemas: " + String.join(", ", missing));
        }
    }

    private void restoreAnthropicAssistantContent(ObjectNode root,
                                                   ProviderRequestContext.Snapshot snapshot) {
        if (snapshot.anthropicAssistantContents().isEmpty()) {
            return;
        }
        JsonNode messagesNode = root.get("messages");
        if (!(messagesNode instanceof ArrayNode messages)) {
            throw new NonRetriableException("LangChain4j omitted Anthropic messages");
        }

        int assistantIndex = 0;
        for (JsonNode messageNode : messages) {
            if (!(messageNode instanceof ObjectNode message)
                    || !"assistant".equals(message.path("role").asText())) {
                continue;
            }
            if (assistantIndex >= snapshot.anthropicAssistantContents().size()) {
                throw new NonRetriableException("Anthropic assistant history shape changed during mapping");
            }

            // MessageMapper 与 Snapshot 使用同一套 assistant turn 分组规则，
            // 因此可以按 assistant 出现序号恢复 thinking/text/tool_use 的原始相对顺序。
            ArrayNode content = MAPPER.createArrayNode();
            snapshot.anthropicAssistantContents().get(assistantIndex)
                    .forEach(block -> content.add(block.deepCopy()));
            message.set("content", content);
            assistantIndex++;
        }

        if (assistantIndex != snapshot.anthropicAssistantContents().size()) {
            // 数量不一致说明 LangChain4j 丢失或新增了 assistant turn，继续发送会破坏历史配对。
            throw new NonRetriableException("LangChain4j omitted Anthropic assistant history");
        }
    }

    private HttpRequest rebuild(HttpRequest request, Map<String, List<String>> headers, String body) {
        // HttpRequest 没有就地修改 API，只能用原 method/url 和修复后的 header/body 重建。
        HttpRequest.Builder builder = HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(headers);
        if (body != null) {
            builder.body(body);
        } else {
            // 当前聊天请求使用 JSON body；这里仍保留 multipart/form-data 字段，避免装饰器改变通用接口语义。
            Map<String, String> fields = request.formDataFields();
            Map<String, FormDataFile> files = request.formDataFiles();
            if (fields != null) {
                builder.formDataFields(fields);
            }
            if (files != null) {
                builder.formDataFiles(files);
            }
        }
        return builder.build();
    }

    private static Map<String, List<String>> mutableHeaders(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, values) ->
                    copy.put(name, values == null ? List.of() : List.copyOf(values)));
        }
        return copy;
    }

    private static void removeHeader(Map<String, List<String>> headers, String target) {
        List<String> names = new ArrayList<>(headers.keySet());
        names.stream().filter(name -> name.equalsIgnoreCase(target)).forEach(headers::remove);
    }
}
