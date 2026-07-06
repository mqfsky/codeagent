package minicode.session.model;

import minicode.context.compact.CompactMetadata;
import minicode.core.message.ChatMessage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话日志中的一条事件。
 *
 * @param type 类型或事件类型
 * @param uuid 事件 uuid
 * @param timestamp 事件或数据生成时间
 * @param sessionId 当前会话 id
 * @param cwd 当前 workspace 工作目录
 * @param parentUuid 物理父事件 uuid；为空表示没有
 * @param logicalParentUuid 逻辑父事件 uuid；为空表示没有
 * @param message 结果说明消息
 * @param meta 会话元事件草稿；为空表示普通消息事件
 * @param compactMetadata 压缩元数据；为空表示非压缩边界事件
 */
public record SessionEvent(SessionEventType type, String uuid, Instant timestamp, String sessionId, String cwd,
                           Optional<String> parentUuid, Optional<String> logicalParentUuid,
                           Optional<ChatMessage> message, Optional<MetaSessionEventDraft> meta,
                           Optional<CompactMetadata> compactMetadata) {
    public SessionEvent {
        type = Objects.requireNonNull(type, "type");
        requireText(uuid, "uuid");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        requireText(sessionId, "sessionId");
        requireText(cwd, "cwd");
        parentUuid = Objects.requireNonNull(parentUuid, "parentUuid");
        logicalParentUuid = Objects.requireNonNull(logicalParentUuid, "logicalParentUuid");
        message = Objects.requireNonNull(message, "message");
        meta = Objects.requireNonNull(meta, "meta");
        compactMetadata = Objects.requireNonNull(compactMetadata, "compactMetadata");
        validate(type, message, meta, compactMetadata);
    }

    public static SessionEvent create(SessionEventType type, String uuid, Instant timestamp, String sessionId,
                                      String cwd, Optional<String> parentUuid, Optional<String> logicalParentUuid,
                                      Optional<ChatMessage> message, Optional<MetaSessionEventDraft> meta) {
        return new SessionEvent(type, uuid, timestamp, sessionId, cwd, parentUuid, logicalParentUuid, message, meta,
                Optional.empty());
    }

    public static SessionEvent create(SessionEventType type, String uuid, Instant timestamp, String sessionId,
                                      String cwd, Optional<String> parentUuid, Optional<String> logicalParentUuid,
                                      Optional<ChatMessage> message, Optional<MetaSessionEventDraft> meta,
                                      Optional<CompactMetadata> compactMetadata) {
        return new SessionEvent(type, uuid, timestamp, sessionId, cwd, parentUuid, logicalParentUuid, message, meta,
                compactMetadata);
    }

    public static SessionEvent message(String uuid, Instant timestamp, String sessionId, String cwd,
                                       Optional<String> parentUuid, Optional<String> logicalParentUuid,
                                       ChatMessage message) {
        return create(SessionEventType.MESSAGE, uuid, timestamp, sessionId, cwd, parentUuid, logicalParentUuid,
                Optional.of(message), Optional.empty(), Optional.empty());
    }

    public static SessionEvent meta(String uuid, Instant timestamp, String sessionId, String cwd,
                                    Optional<String> parentUuid, Optional<String> logicalParentUuid,
                                    MetaSessionEventDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return create(draft.eventType(), uuid, timestamp, sessionId, cwd, parentUuid, logicalParentUuid,
                Optional.empty(), Optional.of(draft), Optional.empty());
    }

    public static SessionEvent compactBoundary(String uuid, Instant timestamp, String sessionId, String cwd,
                                               Optional<String> parentUuid, Optional<String> logicalParentUuid) {
        return create(SessionEventType.COMPACT_BOUNDARY, uuid, timestamp, sessionId, cwd, parentUuid, logicalParentUuid,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static SessionEvent compactBoundary(String uuid, Instant timestamp, String sessionId, String cwd,
                                               Optional<String> parentUuid, Optional<String> logicalParentUuid,
                                               CompactMetadata metadata) {
        return create(SessionEventType.COMPACT_BOUNDARY, uuid, timestamp, sessionId, cwd, parentUuid, logicalParentUuid,
                Optional.empty(), Optional.empty(), Optional.of(metadata));
    }

    private static void validate(SessionEventType type, Optional<ChatMessage> message,
                                 Optional<MetaSessionEventDraft> meta, Optional<CompactMetadata> compactMetadata) {
        if (type == SessionEventType.MESSAGE) {
            if (message.isEmpty() || meta.isPresent() || compactMetadata.isPresent()) {
                throw new IllegalArgumentException("MESSAGE event requires message and no meta payload");
            }
            return;
        }
        if (type == SessionEventType.COMPACT_BOUNDARY) {
            if (message.isPresent() || meta.isPresent()) {
                throw new IllegalArgumentException("COMPACT_BOUNDARY must not carry message or meta draft");
            }
            return;
        }
        if (message.isPresent() || compactMetadata.isPresent() || meta.isEmpty() || meta.get().eventType() != type) {
            throw new IllegalArgumentException(type + " meta event requires matching meta draft and no message");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
