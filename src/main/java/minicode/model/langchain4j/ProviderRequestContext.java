package minicode.model.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.config.ProviderKind;
import minicode.core.message.AssistantMessage;
import minicode.core.message.AssistantProgressMessage;
import minicode.core.message.AssistantThinkingMessage;
import minicode.core.message.AssistantToolCallMessage;
import minicode.core.message.ChatMessage;
import minicode.model.ProviderThinkingBlock;
import minicode.tools.api.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 在一次同步 LangChain4j 调用期间携带 Provider 无损数据。
 *
 * <p>LangChain4j 只建模了可跨 Provider 复用的 JSON Schema 与 thinking 子集。
 * CodeAgent 把原始工具 Schema 和 Anthropic assistant content 暂存在这里，
 * 让 HTTP 兼容层在请求真正发出前恢复原始报文。</p>
 *
 * <p>当前实现专门服务于同步 {@code ChatModel}：ThreadLocal 会覆盖一次同步调用及其内部重试。
 * 如果以后改成 Streaming/SSE，异步回调可能跨线程，必须重新设计上下文传播和响应兼容逻辑。</p>
 */
final class ProviderRequestContext {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ThreadLocal<Snapshot> current = new ThreadLocal<>();

    Snapshot current() {
        return current.get();
    }

    /**
     * 只在当前同步模型调用及其内部重试期间绑定快照。
     *
     * <p>使用 ThreadLocal 是为了让最底层 HTTP Client 取得本次调用数据，同时不把
     * LangChain4j 类型或兼容字段暴露给 Runtime。finally 必须清理，防止线程复用时串请求。</p>
     */
    <T> T within(Snapshot snapshot, Supplier<T> action) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(action, "action");
        Snapshot previous = current.get();
        current.set(snapshot);
        try {
            return action.get();
        } finally {
            // 支持同线程嵌套调用：有外层上下文时恢复外层，没有时彻底 remove。
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    static Snapshot snapshot(ProviderKind provider, List<Tool> tools, List<ChatMessage> messages) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(messages, "messages");

        // 以工具名建立原始 Schema 索引。HTTP 层会按名称覆盖 LangChain4j 序列化后的 Schema，
        // 因而工具名重复时无法可靠配对，必须在发请求前直接拒绝。
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        for (Tool tool : tools) {
            String name = tool.metadata().name();
            JsonNode previous = schemas.put(name, tool.inputSchema().deepCopy());
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate tool schema: " + name);
            }
        }

        // Snapshot 构造器会再次深拷贝，确保后续 Registry 或消息对象变化不会污染本次重试。
        return new Snapshot(provider, schemas, anthropicAssistantContents(messages));
    }

    private static List<List<JsonNode>> anthropicAssistantContents(List<ChatMessage> messages) {
        List<List<JsonNode>> turns = new ArrayList<>();
        List<JsonNode> currentTurn = null;

        for (ChatMessage message : messages) {
            if (isAssistant(message)) {
                // 与 MessageMapper 使用完全相同的连续 assistant 分组边界，
                // HTTP 层才能按 assistant 序号把原始 content 精确写回对应消息。
                if (currentTurn == null) {
                    currentTurn = new ArrayList<>();
                }
                appendAssistantContent(currentTurn, message);
            } else if (currentTurn != null) {
                // 任意非 assistant 消息都会结束当前 Provider assistant turn。
                if (!currentTurn.isEmpty()) {
                    turns.add(List.copyOf(currentTurn));
                }
                currentTurn = null;
            }
        }

        if (currentTurn != null && !currentTurn.isEmpty()) {
            turns.add(List.copyOf(currentTurn));
        }
        return List.copyOf(turns);
    }

    private static boolean isAssistant(ChatMessage message) {
        return message instanceof AssistantThinkingMessage
                || message instanceof AssistantMessage
                || message instanceof AssistantProgressMessage
                || message instanceof AssistantToolCallMessage;
    }

    private static void appendAssistantContent(List<JsonNode> content, ChatMessage message) {
        if (message instanceof AssistantThinkingMessage thinking) {
            // thinking/signature/redacted block 必须保留字段和值以及相对顺序。
            for (ProviderThinkingBlock block : thinking.blocks()) {
                content.add(block.raw().deepCopy());
            }
            return;
        }
        if (message instanceof AssistantMessage assistant) {
            content.add(textBlock(assistant.content()));
            return;
        }
        if (message instanceof AssistantProgressMessage progress) {
            // 用标签保存 progress 语义，下一次响应映射仍能识别它不是最终答复。
            content.add(textBlock("<progress>\n" + progress.content() + "\n</progress>"));
            return;
        }
        if (message instanceof AssistantToolCallMessage toolCall) {
            // 手工重建 Anthropic tool_use block，保持原 tool id、名称与 JSON 输入。
            ObjectNode block = JSON.objectNode();
            block.put("type", "tool_use");
            block.put("id", toolCall.toolUseId());
            block.put("name", toolCall.toolName());
            block.set("input", toolCall.input().deepCopy());
            content.add(block);
        }
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JSON.objectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    record Snapshot(ProviderKind provider, Map<String, JsonNode> toolSchemas,
                    List<List<JsonNode>> anthropicAssistantContents) {
        Snapshot {
            provider = Objects.requireNonNull(provider, "provider");

            // Snapshot 对外只暴露不可变深拷贝，重试期间始终复用同一份稳定数据。
            toolSchemas = immutableSchemas(toolSchemas);
            anthropicAssistantContents = immutableTurns(anthropicAssistantContents);
        }

        private static Map<String, JsonNode> immutableSchemas(Map<String, JsonNode> source) {
            Map<String, JsonNode> copy = new LinkedHashMap<>();
            Objects.requireNonNull(source, "toolSchemas")
                    .forEach((name, schema) -> copy.put(name, schema.deepCopy()));
            return Map.copyOf(copy);
        }

        private static List<List<JsonNode>> immutableTurns(List<List<JsonNode>> source) {
            List<List<JsonNode>> copy = new ArrayList<>();
            for (List<JsonNode> turn : Objects.requireNonNull(source, "anthropicAssistantContents")) {
                List<JsonNode> blocks = new ArrayList<>();
                turn.forEach(block -> blocks.add(block.deepCopy()));
                copy.add(List.copyOf(blocks));
            }
            return List.copyOf(copy);
        }
    }
}
