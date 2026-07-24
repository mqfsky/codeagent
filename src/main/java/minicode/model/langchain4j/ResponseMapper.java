package minicode.model.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicChatResponseMetadata;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import minicode.config.ProviderKind;
import minicode.core.step.AgentStep;
import minicode.core.step.AssistantKind;
import minicode.core.step.AssistantStep;
import minicode.core.step.ContentKind;
import minicode.core.step.ToolCallsStep;
import minicode.model.ProviderRequestException;
import minicode.model.ProviderThinkingBlock;
import minicode.model.ProviderUsage;
import minicode.model.StepDiagnostics;
import minicode.tools.api.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把 LangChain4j 响应转换成 CodeAgent 与 Provider 无关的 Step。
 *
 * <p>这个映射器只生成 {@link AgentStep}，既不执行工具，也不推进 AgentLoop。
 * 工具执行和后续循环仍由 CodeAgent 原有的 {@code AgentLoop} 与 {@code ToolRegistry} 负责。</p>
 */
public final class ResponseMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN_ANTHROPIC_BLOCK_TYPES = Set.of(
            "text", "tool_use", "thinking", "redacted_thinking"
    );

    /**
     * 将一次模型响应归一化为文本 Step 或工具调用 Step。
     *
     * @param provider 当前 Provider，用来选择协议特有的诊断信息
     * @param response LangChain4j 返回的同步响应
     * @return Runtime 可以直接处理的 {@link AssistantStep} 或 {@link ToolCallsStep}
     */
    public AgentStep map(ProviderKind provider, ChatResponse response) {
        ProviderKind actualProvider = Objects.requireNonNull(provider, "provider");
        ChatResponse actualResponse = Objects.requireNonNull(response, "response");
        AiMessage aiMessage = Objects.requireNonNull(actualResponse.aiMessage(), "response.aiMessage");

        // 分别解析正文语义、工具调用、Provider 诊断和 token 用量，再统一组装 AgentStep。
        ParsedText parsedText = parseAssistantText(aiMessage.text());
        List<ToolCall> toolCalls = mapToolCalls(aiMessage.toolExecutionRequests());
        ResponseDetails details = responseDetails(actualProvider, actualResponse, aiMessage);
        Optional<ProviderUsage> usage = providerUsage(actualProvider, actualResponse.metadata());
        StepDiagnostics diagnostics = new StepDiagnostics(
                details.stopReason(),
                details.blockTypes(),
                details.ignoredBlockTypes()
        );

        if (!toolCalls.isEmpty()) {
            // 同一响应可以同时包含提示文本和工具调用。只要存在工具调用，就必须返回
            // ToolCallsStep 交给 AgentLoop 执行；伴随文本仍保存在 content 中，不会丢失。
            return new ToolCallsStep(
                    toolCalls,
                    optionalText(parsedText.content()),
                    parsedText.kind() == AssistantKind.PROGRESS
                            ? ContentKind.PROGRESS
                            : ContentKind.UNSPECIFIED,
                    details.thinkingBlocks(),
                    Optional.of(diagnostics),
                    usage
            );
        }

        // 没有工具调用时才生成纯文本 Step，空文本也保留给 AgentLoop 的既有恢复逻辑判断。
        return new AssistantStep(
                parsedText.content(),
                parsedText.kind(),
                details.thinkingBlocks(),
                Optional.of(diagnostics),
                usage
        );
    }

    private List<ToolCall> mapToolCalls(List<ToolExecutionRequest> requests) {
        List<ToolExecutionRequest> actualRequests = List.copyOf(
                Objects.requireNonNull(requests, "toolExecutionRequests"));
        List<ToolCall> calls = new ArrayList<>(actualRequests.size());
        Set<String> ids = new HashSet<>();

        for (int index = 0; index < actualRequests.size(); index++) {
            ToolExecutionRequest request = Objects.requireNonNull(
                    actualRequests.get(index), "toolExecutionRequest[" + index + "]");
            String id = requireToolText(request.id(), "id", index);
            String name = requireToolText(request.name(), "name", index);

            // tool call id 用于后续结果配对，重复 id 会让 ToolRegistry 无法确定结果归属，
            // 因此必须在任何工具执行之前直接拒绝整份 Provider 响应。
            if (!ids.add(id)) {
                throw invalidResponse("Duplicate tool call id: " + id, null);
            }

            JsonNode input;
            try {
                // LangChain4j 把 arguments 暴露为 JSON 字符串，这里解析回 CodeAgent 使用的 JsonNode。
                input = JSON.readTree(requireToolText(request.arguments(), "arguments", index));
            } catch (JsonProcessingException exception) {
                throw invalidResponse("Tool call arguments are not valid JSON for " + name, exception);
            }
            if (input == null || !input.isObject()) {
                // CodeAgent 工具协议只接受 object 根参数，不生成新 id，也不把坏参数替换成 {}。
                throw invalidResponse("Tool call arguments must be a JSON object for " + name, null);
            }
            calls.add(new ToolCall(id, name, input));
        }
        return List.copyOf(calls);
    }

    private ResponseDetails responseDetails(ProviderKind provider, ChatResponse response, AiMessage aiMessage) {
        if (provider == ProviderKind.ANTHROPIC) {
            // Anthropic 需要从 raw response 补回 stop_reason 与原始 content block。
            return anthropicResponseDetails(response, aiMessage);
        }

        // OpenAI-compatible 的标准字段可直接从 LangChain4j 通用模型读取。
        List<String> blockTypes = new ArrayList<>();
        blockTypes.add(provider == ProviderKind.OPENAI_COMPATIBLE ? "openai_message" : "message");
        List<ProviderThinkingBlock> thinkingBlocks = genericThinkingBlocks(aiMessage.thinking());
        if (!thinkingBlocks.isEmpty()) {
            blockTypes.add("thinking");
        }
        if (aiMessage.hasToolExecutionRequests()) {
            blockTypes.add("tool_calls");
        }
        return new ResponseDetails(
                stopReason(provider, response.finishReason()),
                blockTypes,
                List.of(),
                thinkingBlocks
        );
    }

    private ResponseDetails anthropicResponseDetails(ChatResponse response, AiMessage aiMessage) {
        List<String> blockTypes = new ArrayList<>();
        LinkedHashSet<String> ignoredBlockTypes = new LinkedHashSet<>();
        List<ProviderThinkingBlock> thinkingBlocks = new ArrayList<>();
        Optional<String> stopReason = Optional.empty();

        // LangChain4j 的通用 FinishReason 和 thinking 字段无法完整表达 pause_turn、
        // redacted_thinking、signature 与未知 block，因此 raw HTTP body 才是无损真值。
        Optional<JsonNode> rawBody = anthropicRawBody(response.metadata(), ignoredBlockTypes);
        if (rawBody.isPresent()) {
            JsonNode root = rawBody.orElseThrow();
            stopReason = optionalText(root.path("stop_reason").asText(""));
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    String type = optionalText(block.path("type").asText("")).orElse("<missing>");
                    blockTypes.add(type);
                    if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                        // 深拷贝完整 block，保留 signature、data 以及未来扩展字段。
                        thinkingBlocks.add(new ProviderThinkingBlock(type, block.deepCopy()));
                    } else if (!KNOWN_ANTHROPIC_BLOCK_TYPES.contains(type)) {
                        // 未知 block 不参与 Runtime 行为，但记录到 diagnostics 便于定位协议扩展。
                        ignoredBlockTypes.add(type);
                    }
                }
            }
        }

        if (blockTypes.isEmpty()) {
            // metadata 不含 raw body 时才回退到 LangChain4j 已解析的通用字段。
            if (optionalText(aiMessage.text()).isPresent()) {
                blockTypes.add("text");
            }
            if (optionalText(aiMessage.thinking()).isPresent()) {
                blockTypes.add("thinking");
            }
            if (aiMessage.hasToolExecutionRequests()) {
                blockTypes.add("tool_use");
            }
        }
        if (thinkingBlocks.isEmpty()) {
            // raw body 没有 thinking 时，用 LangChain4j 的 thinking 字符串构造通用 block。
            thinkingBlocks.addAll(genericThinkingBlocks(aiMessage.thinking()));
        }
        if (stopReason.isEmpty()) {
            // raw stop_reason 缺失时，再使用 FinishReason 的标准映射。
            stopReason = stopReason(ProviderKind.ANTHROPIC, response.finishReason());
        }

        return new ResponseDetails(
                stopReason,
                List.copyOf(blockTypes),
                List.copyOf(ignoredBlockTypes),
                List.copyOf(thinkingBlocks)
        );
    }

    private Optional<JsonNode> anthropicRawBody(ChatResponseMetadata metadata,
                                                LinkedHashSet<String> ignoredBlockTypes) {
        // 只有 Anthropic 同步模型的 metadata 携带 SuccessfulHttpResponse。
        if (!(metadata instanceof AnthropicChatResponseMetadata anthropicMetadata)
                || anthropicMetadata.rawHttpResponse() == null) {
            return Optional.empty();
        }
        String body = anthropicMetadata.rawHttpResponse().body();
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(body);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (JsonProcessingException exception) {
            // raw diagnostics 解析失败不能覆盖 LangChain4j 已成功解析出的正文，
            // 记录诊断后继续使用通用字段回退。
            ignoredBlockTypes.add("invalid_raw_response");
            return Optional.empty();
        }
    }

    private List<ProviderThinkingBlock> genericThinkingBlocks(String thinking) {
        Optional<String> content = optionalText(thinking);
        if (content.isEmpty()) {
            return List.of();
        }

        // OpenAI-compatible 或缺少 raw Anthropic 响应时，构造统一格式的 thinking block。
        ObjectNode raw = JSON.createObjectNode();
        raw.put("type", "thinking");
        raw.put("thinking", content.orElseThrow());
        return List.of(new ProviderThinkingBlock("thinking", raw));
    }

    private Optional<ProviderUsage> providerUsage(ProviderKind provider, ChatResponseMetadata metadata) {
        if (metadata == null || metadata.tokenUsage() == null) {
            return Optional.empty();
        }
        TokenUsage tokenUsage = metadata.tokenUsage();
        int input = tokenCount(tokenUsage.inputTokenCount(), "inputTokenCount");
        int output = tokenCount(tokenUsage.outputTokenCount(), "outputTokenCount");

        if (provider == ProviderKind.ANTHROPIC && tokenUsage instanceof AnthropicTokenUsage anthropicUsage) {
            // 延续旧 Adapter 的统计口径：cache creation/read token 都属于本次输入成本。
            input = addTokenCounts(
                    input,
                    tokenCount(anthropicUsage.cacheCreationInputTokens(), "cacheCreationInputTokens"),
                    tokenCount(anthropicUsage.cacheReadInputTokens(), "cacheReadInputTokens")
            );
            int total = addTokenCounts(input, output);
            return total == 0 ? Optional.empty() : Optional.of(new ProviderUsage(input, output, total));
        }

        // 部分兼容网关不返回 total，此时使用 input + output；返回了 0 但分项非零时也修正为分项之和。
        int fallbackTotal = addTokenCounts(input, output);
        int total = tokenUsage.totalTokenCount() == null
                ? fallbackTotal
                : tokenCount(tokenUsage.totalTokenCount(), "totalTokenCount");
        if (total == 0 && fallbackTotal > 0) {
            total = fallbackTotal;
        }
        return total == 0 ? Optional.empty() : Optional.of(new ProviderUsage(input, output, total));
    }

    private int tokenCount(Integer count, String field) {
        if (count == null) {
            return 0;
        }
        if (count < 0) {
            throw invalidResponse(field + " must be non-negative", null);
        }
        return count;
    }

    private int addTokenCounts(int... counts) {
        int result = 0;
        try {
            for (int count : counts) {
                // 使用 addExact，防止异常大的 Provider 数值发生整数溢出后变成负数。
                result = Math.addExact(result, count);
            }
            return result;
        } catch (ArithmeticException exception) {
            throw invalidResponse("Provider token usage exceeds supported integer range", exception);
        }
    }

    private Optional<String> stopReason(ProviderKind provider, FinishReason finishReason) {
        if (finishReason == null) {
            return Optional.empty();
        }
        return Optional.of(switch (finishReason) {
            // 映射回 CodeAgent 历史上使用的 Provider 字符串，保持 AgentLoop 判断兼容。
            case LENGTH -> "max_tokens";
            case STOP -> provider == ProviderKind.ANTHROPIC ? "end_turn" : "stop";
            case TOOL_EXECUTION -> provider == ProviderKind.ANTHROPIC ? "tool_use" : "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            case OTHER -> "other";
        });
    }

    private ParsedText parseAssistantText(String raw) {
        String trimmed = raw == null ? "" : raw.trim();

        // 保留 CodeAgent 既有的两套标记写法；去掉标记后再把语义类型交给 AgentLoop。
        if (trimmed.startsWith("<final>")) {
            return new ParsedText(
                    trimmed.substring("<final>".length()).replaceAll("(?i)</final>", "").trim(),
                    AssistantKind.FINAL
            );
        }
        if (trimmed.startsWith("[FINAL]")) {
            return new ParsedText(trimmed.substring("[FINAL]".length()).trim(), AssistantKind.FINAL);
        }
        if (trimmed.startsWith("<progress>")) {
            return new ParsedText(
                    trimmed.substring("<progress>".length()).replaceAll("(?i)</progress>", "").trim(),
                    AssistantKind.PROGRESS
            );
        }
        if (trimmed.startsWith("[PROGRESS]")) {
            return new ParsedText(trimmed.substring("[PROGRESS]".length()).trim(), AssistantKind.PROGRESS);
        }
        return new ParsedText(trimmed, AssistantKind.UNSPECIFIED);
    }

    private Optional<String> optionalText(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private String requireToolText(String value, String field, int index) {
        if (value == null || value.isBlank()) {
            throw invalidResponse("Tool call " + field + " must not be blank at index " + index, null);
        }
        return value;
    }

    private ProviderRequestException invalidResponse(String message, Throwable cause) {
        // 响应结构已经确定不合法，重试同一响应没有意义，统一标记为不可重试。
        return new ProviderRequestException(message, Optional.empty(), false, cause);
    }

    private record ParsedText(String content, AssistantKind kind) {
    }

    private record ResponseDetails(Optional<String> stopReason,
                                   List<String> blockTypes,
                                   List<String> ignoredBlockTypes,
                                   List<ProviderThinkingBlock> thinkingBlocks) {
        private ResponseDetails {
            stopReason = Objects.requireNonNull(stopReason, "stopReason");
            blockTypes = List.copyOf(Objects.requireNonNull(blockTypes, "blockTypes"));
            ignoredBlockTypes = List.copyOf(Objects.requireNonNull(ignoredBlockTypes, "ignoredBlockTypes"));
            thinkingBlocks = List.copyOf(Objects.requireNonNull(thinkingBlocks, "thinkingBlocks"));
        }
    }
}
