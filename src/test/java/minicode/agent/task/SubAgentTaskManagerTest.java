package minicode.agent.task;

import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;
import minicode.core.message.UserMessage;
import minicode.core.turn.CancellationToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTaskManagerTest {
    @Test
    void backgroundTaskUsesProcessLocalIdAndDestructiveNotificationDrain() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AgentTaskExecutor executor = new AwaitingSuccessfulTaskExecutor(
                release, "完整调查结果", true);
        try (SubAgentTaskManager manager = new SubAgentTaskManager(executor)) {
            SubAgentTaskManager.Task submitted = manager.submit(request(AgentType.EXPLORE));

            assertEquals("task_1", submitted.id());
            assertEquals(AgentTaskStatus.PENDING, submitted.status());
            waitUntil(() -> manager.getTask("task_1").status() == AgentTaskStatus.RUNNING);
            release.countDown();
            waitUntil(() -> manager.getTask("task_1").status() == AgentTaskStatus.COMPLETED);

            var first = manager.drainNotifications();
            assertEquals(1, first.size());
            assertEquals("task_1", first.getFirst().taskId());
            assertEquals("完整调查结果", first.getFirst().output());
            assertTrue(manager.drainNotifications().isEmpty());
        }
    }

    @Test
    void generalPurposeCanRunInBackgroundWithoutCapacityLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AgentTaskExecutor executor = new AwaitingSuccessfulTaskExecutor(release, "done", false);
        try (SubAgentTaskManager manager = new SubAgentTaskManager(executor)) {
            for (int index = 1; index <= 24; index++) {
                assertEquals("task_" + index, manager.submit(request(AgentType.GENERAL_PURPOSE)).id());
            }
            assertEquals(24, manager.listTasks().size());
            release.countDown();
            waitUntil(() -> manager.listTasks().stream().allMatch(task -> task.status().isTerminal()));
        }
    }

    @Test
    void cancelOnlyInterruptsRunningTaskAndTerminalStateCannotBeOverwritten() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AgentTaskExecutor executor = new InterruptibleTaskExecutor(started, interrupted);
        try (SubAgentTaskManager manager = new SubAgentTaskManager(executor)) {
            String taskId = manager.submit(request(AgentType.PLAN)).id();
            assertTrue(started.await(2, TimeUnit.SECONDS));

            manager.cancelTask(taskId);

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            waitUntil(() -> manager.getTask(taskId).status() == AgentTaskStatus.CANCELLED);
            assertEquals(AgentTaskStatus.CANCELLED, manager.getTask(taskId).status());
            assertEquals(AgentTaskStatus.CANCELLED, manager.drainNotifications().getFirst().status());
        }
    }

    @Test
    void completionWakesRegisteredParentListener() throws Exception {
        CountDownLatch notified = new CountDownLatch(1);
        AgentTaskExecutor executor = new FixedResultTaskExecutor(
                AgentRunResult.completed("done", "FINAL"));
        try (SubAgentTaskManager manager = new SubAgentTaskManager(executor)) {
            manager.setNotificationListener(notified::countDown);
            manager.submit(request(AgentType.EXPLORE));

            assertTrue(notified.await(2, TimeUnit.SECONDS));
            assertTrue(manager.hasNotifications());
            assertFalse(manager.drainNotifications().isEmpty());
        }
    }

    @Test
    void failedBackgroundRunProducesFailedTaskAndNotification() throws Exception {
        AgentTaskExecutor executor = new FixedResultTaskExecutor(AgentRunResult.failed(
                "partial", "MAX_STEPS", "Child agent reached maximum steps"));
        try (SubAgentTaskManager manager = new SubAgentTaskManager(executor)) {
            String taskId = manager.submit(request(AgentType.PLAN)).id();
            waitUntil(() -> manager.getTask(taskId).status() == AgentTaskStatus.FAILED);

            var notification = manager.drainNotifications().getFirst();
            assertEquals(AgentTaskStatus.FAILED, notification.status());
            assertEquals("Child agent reached maximum steps", notification.output());
        }
    }

    @Test
    void notificationSourceUsesTransientUserRoleSystemReminderAndKeepsFullOutput() throws Exception {
        AgentTaskExecutor executor = new FixedResultTaskExecutor(
                AgentRunResult.completed("result with <T> & details", "FINAL"));
        try (SubAgentTaskManager manager = new SubAgentTaskManager(executor)) {
            manager.submit(request(AgentType.EXPLORE));
            waitUntil(manager::hasNotifications);

            var messages = new SubAgentTurnMessageSource(manager).drain("session", "turn");

            assertEquals(1, messages.size());
            String content = ((UserMessage) messages.getFirst()).content();
            assertTrue(content.startsWith("<system-reminder>"));
            assertTrue(content.contains("<task-notification>"));
            assertTrue(content.contains("result with <T> & details"));
            assertTrue(new SubAgentTurnMessageSource(manager).drain("session", "turn").isEmpty());
        }
    }

    private static AgentTaskRequest request(AgentType type) {
        return AgentTaskRequest.create(type, "inspect", "inspect repository",
                "session", "turn", "/work", AgentRunMode.BACKGROUND);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static final class AwaitingSuccessfulTaskExecutor implements AgentTaskExecutor {
        private final CountDownLatch release;
        private final String output;
        private final boolean requireVirtualThread;

        private AwaitingSuccessfulTaskExecutor(CountDownLatch release,
                                               String output,
                                               boolean requireVirtualThread) {
            this.release = release;
            this.output = output;
            this.requireVirtualThread = requireVirtualThread;
        }

        @Override
        public AgentRunResult execute(AgentTaskRequest request,
                                      CancellationToken cancellationToken) throws InterruptedException {
            if (requireVirtualThread) {
                assertTrue(Thread.currentThread().isVirtual());
            }
            release.await(2, TimeUnit.SECONDS);
            return AgentRunResult.completed(output, "FINAL");
        }
    }

    private static final class InterruptibleTaskExecutor implements AgentTaskExecutor {
        private final CountDownLatch started;
        private final CountDownLatch interrupted;

        private InterruptibleTaskExecutor(CountDownLatch started, CountDownLatch interrupted) {
            this.started = started;
            this.interrupted = interrupted;
        }

        @Override
        public AgentRunResult execute(AgentTaskRequest request,
                                      CancellationToken cancellationToken) throws InterruptedException {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        }
    }

    private static final class FixedResultTaskExecutor implements AgentTaskExecutor {
        private final AgentRunResult result;

        private FixedResultTaskExecutor(AgentRunResult result) {
            this.result = result;
        }

        @Override
        public AgentRunResult execute(AgentTaskRequest request, CancellationToken cancellationToken) {
            return result;
        }
    }
}
