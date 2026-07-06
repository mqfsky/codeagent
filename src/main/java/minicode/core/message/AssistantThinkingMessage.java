package minicode.core.message;

import minicode.model.ProviderThinkingBlock;

import java.util.List;
import java.util.Objects;

/**
 * provider 返回的 thinking block 消息。
 *
 * <p>它把模型的思考块与普通助手文本分开保存，便于后续按 provider 协议回放。</p>
 *
 * @param blocks provider 原始 thinking block 列表
 */
public record AssistantThinkingMessage(List<ProviderThinkingBlock> blocks) implements ChatMessage {
    public AssistantThinkingMessage {
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
    }
}
