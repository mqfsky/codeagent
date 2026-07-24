package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ContextSummaryMessage;
import minicode.core.message.ToolResultMessage;
import minicode.model.ProviderThinkingBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把 CodeAgent 持久化消息转换成 LangChain4j 的通用聊天消息。
 *
 * <p>这里不管理记忆，也不执行工具。CodeAgent 会把同一次模型回复拆成 thinking、正文、
 * progress 和多个 tool call 记录；这些连续的 assistant 侧记录必须重新合并成一个
 * {@link AiMessage}，Provider 才能看到正确的单轮消息结构。</p>
 */
public final class MessageMapper {
    static final String CONTEXT_SUMMARY_PREFIX = "[Context Summary from earlier conversation]\n";
    static final String THINKING_SIGNATURE_KEY = "thinking_signature";
    static final String REDACTED_THINKING_KEY = "redacted_thinking";

    /**
     * 按 Provider 消息结构转换一份不可变的会话快照。
     *
     * @param messages CodeAgent 内部的八类消息
     * @return 可直接放入 LangChain4j {@code ChatRequest} 的消息列表
     */
    public List<dev.langchain4j.data.message.ChatMessage> map(
            List<minicode.core.message.ChatMessage> messages) {
        List<minicode.core.message.ChatMessage> source =
                List.copyOf(Objects.requireNonNull(messages, "messages"));
        List<dev.langchain4j.data.message.ChatMessage> mapped = new ArrayList<>();

        // Provider 通常只接受一个位于会话开头的 system 消息。
        // 因此先收集全部 SystemMessage，保持原顺序并合并为第一条消息。
        List<String> systemParts = source.stream()
                .filter(minicode.core.message.SystemMessage.class::isInstance)
                .map(minicode.core.message.SystemMessage.class::cast)
                .map(minicode.core.message.SystemMessage::content)
                .toList();
        if (!systemParts.isEmpty()) {
            String systemText = String.join("\n\n", systemParts);
            if (systemText.isBlank()) {
                throw new IllegalArgumentException("Merged system message must not be blank");
            }
            mapped.add(dev.langchain4j.data.message.SystemMessage.from(systemText));
        }

        AssistantTurnBuilder assistantTurn = null;
        for (minicode.core.message.ChatMessage message : source) {
            if (message instanceof minicode.core.message.SystemMessage) {
                // 中途出现的 system 虽然最终会被提到首条，但它仍然是 assistant 分组边界，
                // 防止 system 前后的两次模型回复被误合并成同一个 AiMessage。
                if (assistantTurn != null) {
                    if (assistantTurn.hasContent()) {
                        mapped.add(assistantTurn.build());
                    }
                    assistantTurn = null;
                }
                continue;
            }
            if (isAssistantSide(message)) {
                // 连续的 thinking、正文、progress、tool call 都属于同一次 Provider 回复。
                if (assistantTurn == null) {
                    assistantTurn = new AssistantTurnBuilder();
                }
                assistantTurn.add(message);
                continue;
            }

            // 用户消息、上下文摘要或工具结果会结束当前 assistant turn。
            if (assistantTurn != null) {
                if (assistantTurn.hasContent()) {
                    mapped.add(assistantTurn.build());
                }
                assistantTurn = null;
            }
            mapped.add(mapNonAssistant(message));
        }

        // 会话可能直接以 assistant 消息结束，需要把最后一个尚未刷出的分组补入结果。
        if (assistantTurn != null && assistantTurn.hasContent()) {
            mapped.add(assistantTurn.build());
        }
        return List.copyOf(mapped);
    }

    private static boolean isAssistantSide(minicode.core.message.ChatMessage message) {
        return message instanceof AssistantThinkingMessage
                || message instanceof AssistantMessage
                || message instanceof AssistantProgressMessage
                || message instanceof AssistantToolCallMessage;
    }

    private static dev.langchain4j.data.message.ChatMessage mapNonAssistant(
            minicode.core.message.ChatMessage message) {
        if (message instanceof minicode.core.message.UserMessage user) {
            return dev.langchain4j.data.message.UserMessage.from(user.content());
        }
        if (message instanceof ContextSummaryMessage summary) {
            // ContextSummary 在 Runtime 中是独立类型；到 Provider 层仍按带固定前缀的用户消息回放，
            // 保持旧 Adapter 的语义，同时让模型知道它不是新的用户指令。
            return dev.langchain4j.data.message.UserMessage.from(
                    CONTEXT_SUMMARY_PREFIX + summary.content());
        }
        if (message instanceof ToolResultMessage result) {
            // id 和 toolName 用来与之前的 tool call 精确配对，isError 不能丢失。
            return ToolExecutionResultMessage.builder()
                    .id(result.toolUseId())
                    .toolName(result.toolName())
                    .text(result.content())
                    .isError(result.error())
                    .build();
        }
        throw new IllegalArgumentException("Unsupported CodeAgent message: "
                + message.getClass().getName());
    }

    private static final class AssistantTurnBuilder {
        private final List<String> textParts = new ArrayList<>();
        private final List<String> thinkingParts = new ArrayList<>();
        private final List<String> thinkingSignatures = new ArrayList<>();
        private final List<String> redactedThinking = new ArrayList<>();
        private final List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        private boolean hasText;
        private boolean hasThinking;
        private boolean hasProviderThinkingBlock;

        void add(minicode.core.message.ChatMessage message) {
            if (message instanceof AssistantMessage assistant) {
                addText(assistant.content());
                return;
            }
            if (message instanceof AssistantProgressMessage progress) {
                // progress 标签会在响应侧被识别为中间进展，因此历史回放时也要保留标签。
                addText("<progress>\n" + progress.content() + "\n</progress>");
                return;
            }
            if (message instanceof AssistantToolCallMessage toolCall) {
                // 工具输入在 CodeAgent 中已经是 JsonNode；LangChain4j 要求 arguments 为 JSON 字符串。
                toolRequests.add(ToolExecutionRequest.builder()
                        .id(toolCall.toolUseId())
                        .name(toolCall.toolName())
                        .arguments(toolCall.input().toString())
                        .build());
                return;
            }
            if (message instanceof AssistantThinkingMessage thinking) {
                thinking.blocks().forEach(this::addThinkingBlock);
                return;
            }
            throw new IllegalArgumentException("Unsupported assistant message: "
                    + message.getClass().getName());
        }

        private void addThinkingBlock(ProviderThinkingBlock block) {
            JsonNode raw = block.raw();
            hasProviderThinkingBlock = true;
            if ("redacted_thinking".equals(block.type())) {
                // redacted_thinking 没有可读推理文本，只能通过专用 attribute 暂存。
                // Anthropic 最终请求还会由 HTTP 兼容层恢复完整原始 block。
                redactedThinking.add(textValue(raw.get("data")));
                return;
            }

            // 不同兼容 Provider 可能使用 thinking、reasoning 或 data 字段，
            // 这里把可表示的推理文本合并到 LangChain4j 的 thinking 字段。
            StringBuilder thinking = new StringBuilder();
            appendText(thinking, raw.get("thinking"));
            appendText(thinking, raw.get("reasoning"));
            appendText(thinking, raw.get("data"));
            if (!thinking.isEmpty()) {
                thinkingParts.add(thinking.toString());
                hasThinking = true;
            }

            // signature 是 Anthropic 继续发送 thinking 历史时的校验信息，不能丢失。
            JsonNode signature = raw.get("signature");
            if (signature != null && !signature.isNull() && !signature.asText("").isBlank()) {
                thinkingSignatures.add(signature.asText());
            }

            // 某些 Provider 会把可展示文字放在 thinking block 的 text 字段中。
            JsonNode visibleText = raw.get("text");
            if (visibleText != null && !visibleText.isNull()) {
                addText(textValue(visibleText));
            }

            // 完全无法表示的 block 不能静默忽略，否则 resume 后的历史会被悄悄改写。
            if (!hasRepresentableThinkingContent(raw)) {
                throw new IllegalArgumentException("Unsupported thinking block type: " + block.type());
            }
        }

        boolean hasContent() {
            return hasText || hasThinking || hasProviderThinkingBlock || !toolRequests.isEmpty();
        }

        private boolean hasRepresentableThinkingContent(JsonNode raw) {
            return raw.hasNonNull("thinking")
                    || raw.hasNonNull("reasoning")
                    || raw.hasNonNull("data")
                    || raw.hasNonNull("text")
                    || raw.hasNonNull("signature");
        }

        private void addText(String text) {
            textParts.add(Objects.requireNonNull(text, "text"));
            hasText = true;
        }

        AiMessage build() {
            Map<String, Object> attributes = new LinkedHashMap<>();

            // LangChain4j 1.18 通过这两个约定的 attribute key 读取 signature 和 redacted thinking。
            if (!thinkingSignatures.isEmpty()) {
                attributes.put(THINKING_SIGNATURE_KEY, String.join("\n", thinkingSignatures));
            }
            if (!redactedThinking.isEmpty()) {
                attributes.put(REDACTED_THINKING_KEY, List.copyOf(redactedThinking));
            }

            // text/thinking 没有内容时不主动写空字符串，避免产生无意义的空 assistant 消息。
            AiMessage.Builder builder = AiMessage.builder()
                    .toolExecutionRequests(List.copyOf(toolRequests))
                    .attributes(Map.copyOf(attributes));
            if (hasText) {
                builder.text(String.join("\n", textParts));
            }
            if (hasThinking) {
                builder.thinking(String.join("\n", thinkingParts));
            }
            return builder.build();
        }

        private static void appendText(StringBuilder destination, JsonNode value) {
            if (value != null && !value.isNull()) {
                destination.append(textValue(value));
            }
        }

        private static String textValue(JsonNode value) {
            if (value == null || value.isNull()) {
                return "";
            }
            return value.isTextual() ? value.asText() : value.toString();
        }
    }
}
