package minicode.agent.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 后台 Agent 任务的原子 JSON 快照存储。 */
public final class AgentTaskStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RECOVERY_ERROR = "Task was interrupted because the previous process stopped";

    private final Path root;
    private final long ownerPid;
    private final Optional<Instant> ownerStartedAt;

    public AgentTaskStore(Path root) {
        this(root, ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant());
    }

    AgentTaskStore(Path root, long ownerPid, Optional<Instant> ownerStartedAt) {
        this.root = Objects.requireNonNull(root, "root");
        if (ownerPid <= 0) {
            throw new IllegalArgumentException("ownerPid must be positive");
        }
        this.ownerPid = ownerPid;
        this.ownerStartedAt = Objects.requireNonNull(ownerStartedAt, "ownerStartedAt");
    }

    public synchronized void save(AgentTaskSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Path target = taskFile(snapshot.taskId(), snapshot.cwd(), snapshot.parentSessionId());
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "agent-task-", ".tmp");
            Files.writeString(temporary, serialize(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 目标快照已经处理完毕，遗留临时文件不会影响本次保存结果。
                }
            }
        }
    }

    public synchronized Optional<AgentTaskSnapshot> find(String taskId, String cwd, String parentSessionId) {
        Path file = taskFile(taskId, cwd, parentSessionId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        AgentTaskSnapshot snapshot = read(file);
        if (!snapshot.taskId().equals(taskId)
                || !snapshot.cwd().equals(cwd)
                || !snapshot.parentSessionId().equals(parentSessionId)) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public synchronized List<AgentTaskSnapshot> list(String cwd, String parentSessionId, int limit) {
        return listStored(cwd, parentSessionId, limit).stream()
                .map(StoredTask::snapshot)
                .toList();
    }

    private List<StoredTask> listStored(String cwd, String parentSessionId, int limit) {
        requireText(cwd, "cwd");
        requireText(parentSessionId, "parentSessionId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Path directory = sessionDirectory(cwd, parentSessionId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readStored)
                    .filter(stored -> stored.snapshot().cwd().equals(cwd)
                            && stored.snapshot().parentSessionId().equals(parentSessionId))
                    .sorted(Comparator.comparing(
                                    (StoredTask stored) -> stored.snapshot().submittedAt()).reversed()
                            .thenComparing(stored -> stored.snapshot().taskId()))
                    .limit(limit)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public synchronized List<AgentTaskSnapshot> recover(String cwd, String parentSessionId) {
        return recover(cwd, parentSessionId, Instant.now());
    }

    public synchronized List<AgentTaskSnapshot> recover(String cwd,
                                                        String parentSessionId,
                                                        Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        List<AgentTaskSnapshot> recovered = new ArrayList<>();
        for (StoredTask stored : listStored(cwd, parentSessionId, Integer.MAX_VALUE)) {
            AgentTaskSnapshot snapshot = stored.snapshot();
            if (snapshot.status() == AgentTaskStatus.QUEUED || snapshot.status() == AgentTaskStatus.RUNNING) {
                if (ownedByLiveProcess(stored)) {
                    continue;
                }
                AgentTaskSnapshot interrupted = snapshot.transitionTo(AgentTaskStatus.INTERRUPTED, recoveredAt,
                        snapshot.output(), Optional.of(RECOVERY_ERROR));
                save(interrupted);
                recovered.add(interrupted);
            }
        }
        return List.copyOf(recovered);
    }

    /** 将磁盘上遗留的所有活跃快照标记为已中断，但不恢复执行。 */
    public synchronized List<AgentTaskSnapshot> recoverAll(Instant recoveredAt) {
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredTask> snapshots;
        try (Stream<Path> files = Files.walk(root)) {
            snapshots = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readStored)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        List<AgentTaskSnapshot> recovered = new ArrayList<>();
        for (StoredTask stored : snapshots) {
            AgentTaskSnapshot snapshot = stored.snapshot();
            if (snapshot.status() == AgentTaskStatus.QUEUED || snapshot.status() == AgentTaskStatus.RUNNING) {
                if (ownedByLiveProcess(stored)) {
                    continue;
                }
                AgentTaskSnapshot interrupted = snapshot.transitionTo(AgentTaskStatus.INTERRUPTED, recoveredAt,
                        snapshot.output(), Optional.of(RECOVERY_ERROR));
                save(interrupted);
                recovered.add(interrupted);
            }
        }
        return List.copyOf(recovered);
    }

    private Path taskFile(String taskId, String cwd, String parentSessionId) {
        requireText(taskId, "taskId");
        return sessionDirectory(cwd, parentSessionId).resolve(segmentKey(taskId) + ".json");
    }

    private Path sessionDirectory(String cwd, String parentSessionId) {
        return root.resolve(cwdKey(requireText(cwd, "cwd")))
                .resolve(segmentKey(requireText(parentSessionId, "parentSessionId")));
    }

    private static String cwdKey(String cwd) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cwd.getBytes(StandardCharsets.UTF_8));
    }

    private static String segmentKey(String value) {
        if (value.matches("[A-Za-z0-9._-]+")
                && !value.equals(".")
                && !value.equals("..")
                && !value.startsWith("b64-")) {
            return value;
        }
        return "b64-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String serialize(AgentTaskSnapshot snapshot) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("taskId", snapshot.taskId());
        json.put("agentId", snapshot.agentId());
        json.put("type", snapshot.type().name());
        json.put("description", snapshot.description());
        json.put("parentSessionId", snapshot.parentSessionId());
        json.put("parentTurnId", snapshot.parentTurnId());
        json.put("cwd", snapshot.cwd());
        json.put("status", snapshot.status().name());
        json.put("submittedAt", snapshot.submittedAt().toString());
        snapshot.startedAt().ifPresent(value -> json.put("startedAt", value.toString()));
        snapshot.completedAt().ifPresent(value -> json.put("completedAt", value.toString()));
        snapshot.output().ifPresent(value -> json.put("output", value));
        snapshot.error().ifPresent(value -> json.put("error", value));
        json.put("notificationDelivered", snapshot.notificationDelivered());
        if (!snapshot.status().isTerminal()) {
            json.put("ownerPid", ownerPid);
            ownerStartedAt.ifPresent(value -> json.put("ownerStartedAt", value.toString()));
        }
        try {
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize agent task " + snapshot.taskId(), exception);
        }
    }

    private AgentTaskSnapshot read(Path file) {
        return readStored(file).snapshot();
    }

    private StoredTask readStored(Path file) {
        try {
            JsonNode json = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            AgentTaskSnapshot snapshot = new AgentTaskSnapshot(
                    requiredText(json, "taskId"),
                    requiredText(json, "agentId"),
                    AgentType.valueOf(requiredText(json, "type")),
                    requiredText(json, "description"),
                    requiredText(json, "parentSessionId"),
                    requiredText(json, "parentTurnId"),
                    requiredText(json, "cwd"),
                    AgentTaskStatus.valueOf(requiredText(json, "status")),
                    Instant.parse(requiredText(json, "submittedAt")),
                    optionalInstant(json, "startedAt"),
                    optionalInstant(json, "completedAt"),
                    optionalText(json, "output"),
                    optionalText(json, "error"),
                    json.path("notificationDelivered").asBoolean(false));
            return new StoredTask(snapshot, optionalPositiveLong(json, "ownerPid"),
                    optionalInstant(json, "ownerStartedAt"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid agent task snapshot: " + file, exception);
        }
    }

    private static boolean ownedByLiveProcess(StoredTask stored) {
        if (stored.ownerPid().isEmpty()) {
            return false;
        }
        Optional<ProcessHandle> process = ProcessHandle.of(stored.ownerPid().orElseThrow());
        if (process.isEmpty() || !process.orElseThrow().isAlive()) {
            return false;
        }
        if (stored.ownerStartedAt().isEmpty()) {
            return true;
        }
        Optional<Instant> actualStartedAt = process.orElseThrow().info().startInstant();
        return actualStartedAt.isEmpty()
                || actualStartedAt.orElseThrow().equals(stored.ownerStartedAt().orElseThrow());
    }

    private static Optional<Instant> optionalInstant(JsonNode json, String field) {
        return optionalText(json, field).map(Instant::parse);
    }

    private static Optional<Long> optionalPositiveLong(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
        return Optional.of(value.longValue());
    }

    private static Optional<String> optionalText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return Optional.of(value.textValue());
    }

    private static String requiredText(JsonNode json, String field) {
        return optionalText(json, field)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(field + " is required"));
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record StoredTask(AgentTaskSnapshot snapshot,
                              Optional<Long> ownerPid,
                              Optional<Instant> ownerStartedAt) {
        private StoredTask {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            ownerPid = Objects.requireNonNull(ownerPid, "ownerPid");
            ownerStartedAt = Objects.requireNonNull(ownerStartedAt, "ownerStartedAt");
        }
    }
}
