package minicode.agent.task;

import minicode.agent.model.AgentNotificationMessage;
import minicode.agent.model.AgentTaskSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 基于任务存储的收件箱，保证每个进程最多消费一次通知。 */
public final class AgentInbox {
    public static final int DEFAULT_PREVIEW_LIMIT = 12_000;

    private final AgentTaskStore store;
    private final int previewLimit;
    private final ConcurrentMap<ScopeKey, Object> scopeLocks = new ConcurrentHashMap<>();

    public AgentInbox(AgentTaskStore store) {
        this(store, DEFAULT_PREVIEW_LIMIT);
    }

    public AgentInbox(AgentTaskStore store, int previewLimit) {
        this.store = Objects.requireNonNull(store, "store");
        if (previewLimit <= 0) {
            throw new IllegalArgumentException("previewLimit must be positive");
        }
        this.previewLimit = previewLimit;
    }

    public void enqueue(AgentTaskSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.status().isTerminal()) {
            throw new IllegalArgumentException("Only terminal tasks can be enqueued");
        }
        store.save(snapshot);
    }

    public List<AgentNotificationMessage> drain(String cwd, String parentSessionId) {
        ScopeKey scope = new ScopeKey(cwd, parentSessionId);
        Object lock = scopeLocks.computeIfAbsent(scope, ignored -> new Object());
        synchronized (lock) {
            List<AgentTaskSnapshot> pending = store.list(cwd, parentSessionId, Integer.MAX_VALUE).stream()
                    .filter(snapshot -> snapshot.status().isTerminal() && !snapshot.notificationDelivered())
                    .sorted(Comparator.comparing(snapshot -> snapshot.completedAt().orElse(snapshot.submittedAt())))
                    .toList();
            List<AgentNotificationMessage> messages = new ArrayList<>(pending.size());
            for (AgentTaskSnapshot snapshot : pending) {
                store.save(snapshot.markNotificationDelivered());
                messages.add(toMessage(snapshot));
            }
            return List.copyOf(messages);
        }
    }

    private AgentNotificationMessage toMessage(AgentTaskSnapshot snapshot) {
        Optional<String> preview = snapshot.output().map(value -> value.length() <= previewLimit
                ? value
                : value.substring(0, previewLimit));
        return new AgentNotificationMessage(snapshot.taskId(), snapshot.agentId(), snapshot.type(),
                snapshot.description(), snapshot.status(), preview, snapshot.error(),
                snapshot.completedAt().orElseThrow());
    }

    private record ScopeKey(String cwd, String parentSessionId) {
        private ScopeKey {
            if (Objects.requireNonNull(cwd, "cwd").isBlank()) {
                throw new IllegalArgumentException("cwd must not be blank");
            }
            if (Objects.requireNonNull(parentSessionId, "parentSessionId").isBlank()) {
                throw new IllegalArgumentException("parentSessionId must not be blank");
            }
        }
    }
}
