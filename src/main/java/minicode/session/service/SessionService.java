package minicode.session.service;

import minicode.core.message.ChatMessage;
import minicode.session.factory.SessionEventFactory;
import minicode.session.model.ForkDraft;
import minicode.session.model.ForkMetadata;
import minicode.session.model.RenameDraft;
import minicode.session.plan.PersistenceAction;
import minicode.session.plan.TurnPersistencePlan;
import minicode.session.runner.SessionPersistenceRunner;
import minicode.session.store.SessionMetadata;
import minicode.session.store.SessionStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class SessionService {
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final int MAX_FORK_ID_ATTEMPTS = 5;

    private final SessionStore store;
    private final Supplier<String> sessionIdSupplier;
    private final Clock clock;

    public SessionService(SessionStore store) {
        this(store, () -> UUID.randomUUID().toString());
    }

    public SessionService(SessionStore store, Supplier<String> sessionIdSupplier) {
        this(store, sessionIdSupplier, Clock.systemUTC());
    }

    public SessionService(SessionStore store, Supplier<String> sessionIdSupplier, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<SessionMetadata> list(String cwd) {
        return store.listSessionsByCwd(requireText(cwd, "cwd"));
    }

    public void requireResumable(String cwd, String sessionId) {
        requireExistingInCwd(cwd, sessionId);
    }

    public List<ChatMessage> resumeMessages(String cwd, String sessionId) {
        requireExistingInCwd(cwd, sessionId);
        List<ChatMessage> messages = store.loadMessagesSinceLatestCompactBoundary(sessionId, cwd);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Session has no resumable messages: " + sessionId);
        }
        return messages;
    }

    public void rename(String cwd, String sessionId, String title) {
        String actualTitle = requireTitle(title);
        requireExistingInCwd(cwd, sessionId);
        runnerFor(cwd, sessionId).apply(new TurnPersistencePlan(List.of(
                new PersistenceAction.AppendSessionEventAction(new RenameDraft(actualTitle))
        )));
    }

    /**
     * 从指定的已有 session 创建一个包含相同可恢复消息的新 session。
     *
     * <p>新 session 会记录源 session、源事件和新 session 等 fork 元数据，
     * 然后复制源 session 当前可恢复的消息历史；源 session 本身不会被修改。</p>
     *
     * @param cwd 源 session 所属的工作目录
     * @param sourceSessionId 要复制的源 session 标识
     * @return 新创建且不与当前工作目录中已有 session 冲突的 session 标识
     * @throws IllegalArgumentException 源 session 不存在、不属于指定工作目录或没有可恢复消息时抛出
     * @throws IllegalStateException 多次尝试后仍无法生成唯一的新 session 标识时抛出
     */
    public String fork(String cwd, String sourceSessionId) {
        // 确认源 session 存在于指定工作目录中。
        requireExistingInCwd(cwd, sourceSessionId);

        // 加载源 session 在最近一次 compact 边界之后的可恢复消息。
        List<ChatMessage> messages = resumeMessages(cwd, sourceSessionId);

        // 为 fork 出来的 session 分配一个未被占用的新标识。
        String newSessionId = allocateForkSessionId(cwd);

        // 记录源 session 当前最新事件，建立新旧 session 之间的 fork 关系。
        Optional<String> sourceEventId = store.latestEventUuid(sourceSessionId, cwd);
        // 新的 session 持久化器
        SessionPersistenceRunner runner = runnerFor(cwd, newSessionId);

        // 先写入 fork 元数据，再把源 session 的可恢复消息复制到新 session。
        runner.apply(new TurnPersistencePlan(List.of(
                        // 放了两个 Action
                        new PersistenceAction.AppendSessionEventAction( // 记录“这个新 session 是怎么 fork 出来的”,把这条 session 元事件追加到文件中。
                            new ForkDraft(
                                    new ForkMetadata(
                                        sourceSessionId,
                                        sourceEventId,
                                        newSessionId,
                                        cwd,
                                        Instant.now(clock)
                ))),
                        new PersistenceAction.AppendMessagesAction(messages) // 把从源 session 中读取出来的所有可恢复消息，依次追加到新 session 文件中。
        )));
        /**
         * 最终新 session 文件大致会是
         * fork 元事件
         * 源 session 的第 1 条可恢复消息：
         * {"message":{"role":"assistant_tool_call","toolUseId":"tool-1","toolName":"list_files","input":{"path":"."}}}
         * 源 session 的第 2 条可恢复消息：
         * {"message":{"role":"tool_result","toolUseId":"tool-1","toolName":"list_files","content":"src\npom.xml\nREADME.md","error":false}}
         * 源 session 的第 3 条可恢复消息
         * ...
         */

        // 返回新 sessionId，供调用方切换到 fork 后的会话。
        return newSessionId;
    }

    private SessionPersistenceRunner runnerFor(String cwd, String sessionId) {
        return new SessionPersistenceRunner(store, new SessionEventFactory(
                sessionId,
                cwd,
                clock,
                () -> UUID.randomUUID().toString(),
                store.latestEventUuid(sessionId, cwd)
        ));
    }

    private String allocateForkSessionId(String cwd) {
        for (int attempt = 0; attempt < MAX_FORK_ID_ATTEMPTS; attempt++) {
            // 生成一个 uuid 作为 sessionid
            String candidate = requireSessionId(sessionIdSupplier.get());
            if (store.readMetadata(candidate, cwd).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate unique fork session id after "
                + MAX_FORK_ID_ATTEMPTS + " attempts.");
    }

    // 检查 session 是否在当前 cwd 中
    private void requireExistingInCwd(String cwd, String sessionId) {
        // 检查参数是否合法
        String actualCwd = requireText(cwd, "cwd");
        // 检查 sessionid 是否合法
        String actualSessionId = requireSessionId(sessionId);
        // 当前工作目录中，是否存在指定 session 的元数据。
        if (store.readMetadata(actualSessionId, actualCwd).isPresent()) {
            return;
        }
        // 是否存在其他 cwd 中
        List<String> otherCwds = store.findCwdsForSessionId(actualSessionId).stream()
                .filter(candidate -> !candidate.equals(actualCwd))
                .toList();
        if (!otherCwds.isEmpty()) {
            throw new IllegalArgumentException("Session " + actualSessionId
                    + " belongs to a different cwd: " + otherCwds.getFirst());
        }
        throw new IllegalArgumentException("Session not found: " + actualSessionId);
    }

    private static String requireTitle(String value) {
        if (Objects.requireNonNull(value, "title").isBlank()) {
            throw new IllegalArgumentException("Session title must not be blank.");
        }
        return value.trim();
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSessionId(String sessionId) {
        String value = requireText(sessionId, "sessionId");
        if (!SESSION_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid session id: " + value);
        }
        return value;
    }
}
