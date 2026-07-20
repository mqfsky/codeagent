package minicode.agent.task;

import minicode.agent.event.AgentTaskEvent;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTaskManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void enforcesFourRunningAndSixteenQueuedAndCompletesDurably() throws Exception {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        AgentInbox inbox = new AgentInbox(store);
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AgentTaskExecutor executor = (request, token) -> {
            int current = running.incrementAndGet();
            maxRunning.accumulateAndGet(current, Math::max);
            fourStarted.countDown();
            try {
                release.await();
                return AgentRunResult.completed("done-" + request.taskId(), "FINAL");
            } finally {
                running.decrementAndGet();
            }
        };

        try (SubAgentTaskManager manager = manager(store, inbox, executor, Duration.ofMinutes(1), 4, 16)) {
            for (int index = 0; index < 20; index++) {
                manager.submit(request("task-" + index));
            }
            assertTrue(fourStarted.await(2, TimeUnit.SECONDS));
            assertThrows(AgentTaskRejectedException.class, () -> manager.submit(request("overflow")));
            assertEquals(4, maxRunning.get());

            release.countDown();
            await(() -> manager.list("/work", "session", 100).stream()
                    .allMatch(snapshot -> snapshot.status() == AgentTaskStatus.COMPLETED));
            assertEquals(20, manager.list("/work", "session", 100).size());
        }
    }

    @Test
    void cancellationSetsTokenInterruptsThreadAndCannotRegressTerminalState() throws Exception {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        AgentInbox inbox = new AgentInbox(store);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean tokenObserved = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AgentTaskExecutor executor = (request, token) -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException ignored) {
                interrupted.set(true);
                tokenObserved.set(token.isCancellationRequested());
            }
            return AgentRunResult.completed("late completion", "FINAL");
        };

        try (SubAgentTaskManager manager = manager(store, inbox, executor, Duration.ofMinutes(1), 1, 1)) {
            manager.submit(request("cancel-me"));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            CancelResult first = manager.cancel("cancel-me", "/work", "session", "stop now");
            CancelResult second = manager.cancel("cancel-me", "/work", "session", "stop again");

            assertTrue(first.changed());
            assertFalse(second.changed());
            await(interrupted::get);
            assertTrue(tokenObserved.get());
            await(() -> manager.find("cancel-me", "/work", "session").orElseThrow().status().isTerminal());
            assertEquals(AgentTaskStatus.CANCELLED,
                    manager.find("cancel-me", "/work", "session").orElseThrow().status());
        }
    }

    @Test
    void queuedCancellationReleasesCapacityAndNeverExecutesTask() throws Exception {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        AgentInbox inbox = new AgentInbox(store);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> executed = new CopyOnWriteArrayList<>();
        AgentTaskExecutor executor = (request, token) -> {
            executed.add(request.taskId());
            if (request.taskId().equals("first")) {
                firstStarted.countDown();
                releaseFirst.await();
            }
            return AgentRunResult.completed("done", "FINAL");
        };

        try (SubAgentTaskManager manager = manager(store, inbox, executor, Duration.ofMinutes(1), 1, 1)) {
            manager.submit(request("first"));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            manager.submit(request("cancel-while-queued"));
            assertEquals(AgentTaskStatus.QUEUED,
                    manager.find("cancel-while-queued", "/work", "session").orElseThrow().status());

            CancelResult cancelled = manager.cancel(
                    "cancel-while-queued", "/work", "session", "no longer needed");
            assertTrue(cancelled.changed());
            assertEquals(AgentTaskStatus.CANCELLED, cancelled.snapshot().status());

            // 已取消的排队任务必须在运行中任务退出前释放容量。
            manager.submit(request("replacement"));
            releaseFirst.countDown();
            await(() -> manager.find("replacement", "/work", "session").orElseThrow().status()
                    == AgentTaskStatus.COMPLETED);

            assertFalse(executed.contains("cancel-while-queued"));
            assertEquals(AgentTaskStatus.CANCELLED,
                    store.find("cancel-while-queued", "/work", "session").orElseThrow().status());
        }
    }

    @Test
    void managerCannotCancelNonTerminalTaskOwnedByAnotherManager() throws Exception {
        Path root = tempDir.resolve("shared");
        AgentTaskStore firstStore = new AgentTaskStore(root);
        AgentTaskStore secondStore = new AgentTaskStore(root);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (SubAgentTaskManager first = manager(firstStore, new AgentInbox(firstStore), (request, token) -> {
            started.countDown();
            release.await();
            return AgentRunResult.completed("owner completed", "FINAL");
        }, Duration.ofMinutes(1), 1, 1);
             SubAgentTaskManager second = manager(secondStore, new AgentInbox(secondStore),
                     (request, token) -> AgentRunResult.completed("unused", "FINAL"),
                     Duration.ofMinutes(1), 1, 1)) {
            first.submit(request("owned-elsewhere"));
            assertTrue(started.await(2, TimeUnit.SECONDS));

            assertThrows(IllegalArgumentException.class,
                    () -> second.cancel("owned-elsewhere", "/work", "session", "cross-instance cancel"));
            assertEquals(AgentTaskStatus.RUNNING,
                    second.find("owned-elsewhere", "/work", "session").orElseThrow().status());

            release.countDown();
            await(() -> firstStore.find("owned-elsewhere", "/work", "session").orElseThrow().status()
                    == AgentTaskStatus.COMPLETED);
        }
    }

    @Test
    void timeoutInterruptsExecutionAndCloseMarksRemainingTasksInterrupted() throws Exception {
        AgentTaskStore timeoutStore = new AgentTaskStore(tempDir.resolve("timeout"));
        CountDownLatch timeoutExited = new CountDownLatch(1);
        try (SubAgentTaskManager manager = manager(timeoutStore, new AgentInbox(timeoutStore),
                (request, token) -> {
                    try {
                        while (!token.isCancellationRequested()) {
                            Thread.onSpinWait();
                        }
                        token.throwIfCancellationRequested(minicode.core.turn.CancellationPhase.TOOL_EXECUTION);
                        return AgentRunResult.completed("unexpected", "FINAL");
                    } finally {
                        timeoutExited.countDown();
                    }
                }, Duration.ofMillis(50), 1, 1)) {
            manager.submit(request("timeout"));
            await(() -> manager.find("timeout", "/work", "session").orElseThrow().status()
                    == AgentTaskStatus.TIMED_OUT);
            assertTrue(timeoutExited.await(2, TimeUnit.SECONDS));
            assertEquals(AgentTaskStatus.TIMED_OUT,
                    timeoutStore.find("timeout", "/work", "session").orElseThrow().status());
        }

        AgentTaskStore closeStore = new AgentTaskStore(tempDir.resolve("close"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closeExited = new CountDownLatch(1);
        SubAgentTaskManager manager = manager(closeStore, new AgentInbox(closeStore), (request, token) -> {
            started.countDown();
            try {
                while (!token.isCancellationRequested()) {
                    Thread.onSpinWait();
                }
                token.throwIfCancellationRequested(minicode.core.turn.CancellationPhase.TOOL_EXECUTION);
                return AgentRunResult.completed("unexpected", "FINAL");
            } finally {
                closeExited.countDown();
            }
        }, Duration.ofMinutes(1), 1, 1);
        manager.submit(request("close"));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        manager.close();

        assertEquals(0, closeExited.getCount(), "close must wait for the child execution to stop");
        assertEquals(AgentTaskStatus.INTERRUPTED,
                closeStore.find("close", "/work", "session").orElseThrow().status());
    }

    @Test
    void terminalNotificationAcknowledgementCannotBeReopenedByLifecyclePersistence() {
        AgentTaskStore store = new AgentTaskStore(tempDir);
        AgentInbox inbox = new AgentInbox(store);
        List<minicode.agent.model.AgentNotificationMessage> notifications = new CopyOnWriteArrayList<>();

        try (SubAgentTaskManager manager = new SubAgentTaskManager(store, inbox,
                (request, token) -> AgentRunResult.completed("result", "FINAL"), event -> {
                    if (event instanceof AgentTaskEvent.StateChangedEvent state
                            && state.status().isTerminal()) {
                        notifications.addAll(inbox.drain("/work", "session"));
                    }
                }, Clock.systemUTC(), Duration.ofMinutes(1), 1, 1)) {
            manager.submit(request("notify-once"));
            await(() -> manager.find("notify-once", "/work", "session").orElseThrow().status()
                    == AgentTaskStatus.COMPLETED);

            assertEquals(1, notifications.size());
            assertTrue(inbox.drain("/work", "session").isEmpty());
            assertTrue(store.find("notify-once", "/work", "session")
                    .orElseThrow().notificationDelivered());
        }
    }

    @Test
    void recoveryPersistsInterruptedTasksAndEventFailuresDoNotAffectCompletion() {
        AgentTaskStore store = new AgentTaskStore(tempDir, Long.MAX_VALUE, java.util.Optional.empty());
        AgentTaskSnapshot queued = AgentTaskSnapshot.queued(request("old"));
        store.save(queued);
        List<AgentTaskEvent> events = new ArrayList<>();
        try (SubAgentTaskManager manager = new SubAgentTaskManager(store, new AgentInbox(store),
                (request, token) -> AgentRunResult.completed("ok", "FINAL"), event -> {
                    events.add(event);
                    throw new IllegalStateException("renderer failed");
                }, Clock.systemUTC(), Duration.ofMinutes(1), 1, 1)) {
            assertEquals(AgentTaskStatus.INTERRUPTED,
                    manager.recover("/work", "session").getFirst().status());
            manager.submit(request("new"));
            await(() -> manager.find("new", "/work", "session").orElseThrow().status()
                    == AgentTaskStatus.COMPLETED);
        }
        assertFalse(events.isEmpty());
    }

    @Test
    void startupRecoveryInterruptsAllScopesButPublishesOnlyCurrentScope() {
        AgentTaskStore store = new AgentTaskStore(tempDir, Long.MAX_VALUE, java.util.Optional.empty());
        store.save(AgentTaskSnapshot.queued(request("current")));
        AgentTaskRequest foreign = new AgentTaskRequest(
                "foreign", "agent-foreign", AgentType.PLAN, "description", "prompt",
                "other-session", "other-turn", "/other-work", AgentRunMode.BACKGROUND, Instant.now());
        store.save(AgentTaskSnapshot.queued(foreign));
        List<AgentTaskEvent> events = new CopyOnWriteArrayList<>();

        try (SubAgentTaskManager manager = new SubAgentTaskManager(store, new AgentInbox(store),
                (request, token) -> AgentRunResult.completed("unused", "FINAL"), events::add,
                Clock.systemUTC(), Duration.ofMinutes(1), 1, 1)) {
            manager.recoverPersistedTasks("/work", "session");
        }

        assertEquals(AgentTaskStatus.INTERRUPTED,
                store.find("current", "/work", "session").orElseThrow().status());
        assertEquals(AgentTaskStatus.INTERRUPTED,
                store.find("foreign", "/other-work", "other-session").orElseThrow().status());
        assertEquals(1, events.size());
        AgentTaskEvent.StateChangedEvent event = (AgentTaskEvent.StateChangedEvent) events.getFirst();
        assertEquals("current", event.taskId().orElseThrow());
    }

    private static SubAgentTaskManager manager(AgentTaskStore store,
                                               AgentInbox inbox,
                                               AgentTaskExecutor executor,
                                               Duration timeout,
                                               int maxConcurrent,
                                               int maxQueued) {
        return new SubAgentTaskManager(store, inbox, executor, event -> { }, Clock.systemUTC(), timeout,
                maxConcurrent, maxQueued);
    }

    private static AgentTaskRequest request(String taskId) {
        return new AgentTaskRequest(taskId, "agent-" + taskId, AgentType.EXPLORE, "description", "prompt",
                "session", "turn", "/work", AgentRunMode.BACKGROUND, Instant.now());
    }

    private static void await(Check check) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("condition was not met before timeout");
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate();
    }
}
