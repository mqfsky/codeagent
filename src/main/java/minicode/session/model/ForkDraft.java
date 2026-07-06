package minicode.session.model;

import java.util.Objects;

/**
 * fork session 元事件草稿。
 *
 * @param metadata 压缩元数据
 */
public record ForkDraft(ForkMetadata metadata) implements MetaSessionEventDraft {
    public ForkDraft {
        metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public SessionEventType eventType() {
        return SessionEventType.FORK;
    }
}
