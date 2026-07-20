package minicode.agent.task;

import minicode.agent.model.AgentNotificationMessage;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAtomicallyAndIsolatesTasksByCwdAndSession() throws Exception {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        AgentTaskSnapshot left = AgentTaskSnapshot.queued(request("task-1", "/work/left", "session-a"));
        AgentTaskSnapshot right = AgentTaskSnapshot.queued(request("task-1", "/work/right", "session-a"));

        store.save(left);
        store.save(right);

        assertEquals(left, store.find("task-1", "/work/left", "session-a").orElseThrow());
        assertEquals(right, store.find("task-1", "/work/right", "session-a").orElseThrow());
        assertTrue(store.find("task-1", "/work/left", "session-b").isEmpty());
        String cwdKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("/work/left".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(tempDir.resolve(cwdKey).resolve("session-a").resolve("task-1.json")));
        try (var paths = Files.walk(tempDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void listUsesNewestFirstAndRecoveryInterruptsOnlyActiveTasks() {
        AgentTaskStore store = new AgentTaskStore(tempDir, Long.MAX_VALUE, Optional.empty());
        AgentTaskSnapshot queued = AgentTaskSnapshot.queued(request("queued", "/work", "session"));
        AgentTaskSnapshot running = AgentTaskSnapshot.queued(request("running", "/work", "session"))
                .transitionTo(AgentTaskStatus.RUNNING, Instant.parse("2026-01-01T00:00:03Z"),
                        Optional.empty(), Optional.empty());
        AgentTaskSnapshot completed = AgentTaskSnapshot.queued(request("completed", "/work", "session"))
                .transitionTo(AgentTaskStatus.RUNNING, Instant.parse("2026-01-01T00:00:02Z"),
                        Optional.empty(), Optional.empty())
                .transitionTo(AgentTaskStatus.COMPLETED, Instant.parse("2026-01-01T00:00:04Z"),
                        Optional.of("done"), Optional.empty());
        store.save(queued);
        store.save(running);
        store.save(completed);

        List<AgentTaskSnapshot> recovered = store.recover("/work", "session",
                Instant.parse("2026-01-01T01:00:00Z"));

        assertEquals(2, recovered.size());
        assertTrue(recovered.stream().allMatch(task -> task.status() == AgentTaskStatus.INTERRUPTED));
        assertEquals(AgentTaskStatus.COMPLETED,
                store.find("completed", "/work", "session").orElseThrow().status());
        assertEquals(List.of("completed", "running", "queued"),
                store.list("/work", "session", 100).stream().map(AgentTaskSnapshot::taskId).toList());
    }

    @Test
    void inboxDrainsUndeliveredTerminalTasksOnceAndKeepsFullOutputInStore() {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        AgentInbox inbox = new AgentInbox(store, 4);
        AgentTaskSnapshot completed = AgentTaskSnapshot.queued(request("task", "/work", "session"))
                .transitionTo(AgentTaskStatus.RUNNING, Instant.parse("2026-01-01T00:00:02Z"),
                        Optional.empty(), Optional.empty())
                .transitionTo(AgentTaskStatus.COMPLETED, Instant.parse("2026-01-01T00:00:03Z"),
                        Optional.of("abcdefgh"), Optional.empty());
        inbox.enqueue(completed);

        List<AgentNotificationMessage> first = inbox.drain("/work", "session");

        assertEquals(1, first.size());
        assertEquals(Optional.of("abcd"), first.getFirst().outputPreview());
        assertTrue(inbox.drain("/work", "session").isEmpty());
        AgentTaskSnapshot persisted = store.find("task", "/work", "session").orElseThrow();
        assertEquals(Optional.of("abcdefgh"), persisted.output());
        assertTrue(persisted.notificationDelivered());
    }

    @Test
    void recoverAllInterruptsActiveTasksFromEveryPersistedSession() {
        AgentTaskStore store = new AgentTaskStore(tempDir, Long.MAX_VALUE, Optional.empty());
        AgentTaskSnapshot first = AgentTaskSnapshot.queued(request("first", "/work/a", "session-a"));
        AgentTaskSnapshot second = AgentTaskSnapshot.queued(request("second", "/work/b", "session-b"))
                .transitionTo(AgentTaskStatus.RUNNING, Instant.parse("2026-01-01T00:00:01Z"),
                        Optional.empty(), Optional.empty());
        store.save(first);
        store.save(second);

        List<AgentTaskSnapshot> recovered = store.recoverAll(Instant.parse("2026-01-01T01:00:00Z"));

        assertEquals(2, recovered.size());
        assertEquals(AgentTaskStatus.INTERRUPTED,
                store.find("first", "/work/a", "session-a").orElseThrow().status());
        assertEquals(AgentTaskStatus.INTERRUPTED,
                store.find("second", "/work/b", "session-b").orElseThrow().status());
    }

    @Test
    void recoverAllLeavesTasksOwnedByThisLiveProcessRunning() {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        store.save(AgentTaskSnapshot.queued(request("live", "/work", "session")));

        List<AgentTaskSnapshot> recovered = store.recoverAll(Instant.parse("2026-01-01T01:00:00Z"));

        assertTrue(recovered.isEmpty());
        assertEquals(AgentTaskStatus.QUEUED,
                store.find("live", "/work", "session").orElseThrow().status());
    }

    private static AgentTaskRequest request(String taskId, String cwd, String sessionId) {
        Instant submittedAt = switch (taskId) {
            case "queued" -> Instant.parse("2026-01-01T00:00:00Z");
            case "running" -> Instant.parse("2026-01-01T00:00:01Z");
            case "completed" -> Instant.parse("2026-01-01T00:00:02Z");
            default -> Instant.parse("2026-01-01T00:00:00Z");
        };
        return new AgentTaskRequest(taskId, "agent-" + taskId, AgentType.EXPLORE, "description", "prompt",
                sessionId, "turn", cwd, AgentRunMode.BACKGROUND, submittedAt);
    }
}
