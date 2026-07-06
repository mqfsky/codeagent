package minicode.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * provider 原始 thinking block。
 *
 * @param type 类型或事件类型
 * @param raw provider 返回的原始 JSON
 */
public record ProviderThinkingBlock(String type, JsonNode raw) {
    public ProviderThinkingBlock {
        if (Objects.requireNonNull(type, "type").isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        raw = Objects.requireNonNull(raw, "raw");
    }
}
