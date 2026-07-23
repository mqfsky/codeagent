package minicode.agent.task;

import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与 Mewcode 对齐的进程内后台子 Agent 管理器。
 *
 * <p>任务、结果和通知只保存在内存中。进程退出后不会恢复任务，也不会重投已经取走的通知。</p>
 */
public final class SubAgentTaskManager implements AutoCloseable {
    private static final Runnable NO_NOTIFICATION_LISTENER = new NoNotificationListener();

    public record Task(String id,
                       String name,
                       AgentType type,
                       AgentTaskStatus status,
                       String output,
                       String error) {
    }

    public record TaskNotification(String taskId,
                                   String name,
                                   AgentType type,
                                   AgentTaskStatus status,
                                   String output) {
    }

    private final AgentTaskExecutor taskExecutor;
    private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();
    private final List<TaskNotification> notifications = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger();

    private volatile Runnable notificationListener = NO_NOTIFICATION_LISTENER;
    private boolean closed;

    public SubAgentTaskManager(AgentTaskExecutor taskExecutor) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
    }

    /** 启动一个后台子 Agent，并立即返回其内存任务视图。 */
    public Task submit(AgentTaskRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.runMode() != AgentRunMode.BACKGROUND) {
            throw new IllegalArgumentException("SubAgentTaskManager only accepts BACKGROUND tasks");
        }

        TaskEntry entry;
        synchronized (this) {
            ensureOpen();
            String taskId = "task_" + nextId.incrementAndGet();
            AgentTaskRequest actualRequest = request.withTaskId(taskId);
            entry = new TaskEntry(actualRequest, taskName(actualRequest));
            tasks.put(taskId, entry);
            entry.thread = Thread.ofVirtual()
                    .name("sub-agent-" + taskId)
                    .unstarted(new TaskRunner(this, entry));
            entry.thread.start();
            return snapshot(entry);
        }
    }

    /** 查询当前管理器内的一项任务。 */
    public synchronized Task getTask(String taskId) {
        TaskEntry entry = tasks.get(Objects.requireNonNull(taskId, "taskId"));
        return entry == null ? null : snapshot(entry);
    }

    /** 返回当前进程创建的全部后台任务。 */
    public synchronized List<Task> listTasks() {
        return tasks.values().stream().map(SubAgentTaskManager::snapshot).toList();
    }

    /**
     * 取消当前实例中正在运行的任务。
     *
     * <p>该方法不访问磁盘，也不能取消其他 CodeAgent 进程拥有的任务。</p>
     */
    public void cancelTask(String taskId) {
        Thread thread;
        Runnable listener;
        synchronized (this) {
            TaskEntry entry = tasks.get(Objects.requireNonNull(taskId, "taskId"));
            if (entry == null || entry.status != AgentTaskStatus.RUNNING) {
                return;
            }
            entry.status = AgentTaskStatus.CANCELLED;
            entry.error = "Cancelled";
            entry.cancellationToken.requestCancellation(CancellationSource.USER, "Cancelled by parent agent");
            notifications.add(new TaskNotification(entry.request.taskId(), entry.name, entry.request.type(),
                    AgentTaskStatus.CANCELLED, ""));
            thread = entry.thread;
            listener = notificationListener;
        }
        if (thread != null) {
            thread.interrupt();
        }
        notifyListener(listener);
    }

    /** 取走当前所有通知；读取完成后内存列表立即清空。 */
    public synchronized List<TaskNotification> drainNotifications() {
        List<TaskNotification> result = List.copyOf(notifications);
        notifications.clear();
        return result;
    }

    public synchronized boolean hasNotifications() {
        return !closed && !notifications.isEmpty();
    }

    /** 注册通知到达回调；Renderer 用它在父 Agent 空闲时自动继续执行。 */
    public void setNotificationListener(Runnable listener) {
        Runnable actualListener = Objects.requireNonNull(listener, "listener");
        boolean notifyNow;
        synchronized (this) {
            if (closed) {
                return;
            }
            notificationListener = actualListener;
            notifyNow = !notifications.isEmpty();
        }
        if (notifyNow) {
            notifyListener(actualListener);
        }
    }

    @Override
    public void close() {
        List<Thread> threads = new ArrayList<>();
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            notificationListener = NO_NOTIFICATION_LISTENER;
            for (TaskEntry entry : tasks.values()) {
                if (entry.status == AgentTaskStatus.PENDING || entry.status == AgentTaskStatus.RUNNING) {
                    entry.status = AgentTaskStatus.CANCELLED;
                    entry.error = "Task manager closed";
                    entry.cancellationToken.requestCancellation(CancellationSource.SYSTEM,
                            "Task manager closed");
                    if (entry.thread != null) {
                        threads.add(entry.thread);
                    }
                }
            }
        }
        threads.forEach(Thread::interrupt);
    }

    private void run(TaskEntry entry) {
        synchronized (this) {
            if (closed || entry.status != AgentTaskStatus.PENDING) {
                return;
            }
            entry.status = AgentTaskStatus.RUNNING;
        }

        try {
            AgentRunResult result = Objects.requireNonNull(
                    taskExecutor.execute(entry.request, entry.cancellationToken),
                    "taskExecutor result");
            if (result.cancelled()) {
                finish(entry, AgentTaskStatus.CANCELLED, result.output(),
                        result.error().orElse("Child agent was cancelled"));
            } else if (result.error().isPresent()) {
                finish(entry, AgentTaskStatus.FAILED, result.output(), result.error().orElseThrow());
            } else {
                String output = result.output().isEmpty() ? "(agent produced no output)" : result.output();
                finish(entry, AgentTaskStatus.COMPLETED, output, null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            finish(entry, AgentTaskStatus.FAILED, "", "Interrupted");
        } catch (CancellationRequestedException | CancellationException exception) {
            finish(entry, AgentTaskStatus.CANCELLED, "", message(exception, "Cancelled"));
        } catch (Throwable throwable) {
            finish(entry, AgentTaskStatus.FAILED, "", message(throwable, throwable.getClass().getSimpleName()));
        }
    }

    private void finish(TaskEntry entry, AgentTaskStatus status, String output, String error) {
        Runnable listener;
        synchronized (this) {
            // 取消操作可能先一步进入终态，工作线程不得再覆盖结果。
            if (entry.status.isTerminal()) {
                return;
            }
            entry.status = status;
            entry.output = output == null ? "" : output;
            entry.error = error;
            String notificationOutput = status == AgentTaskStatus.FAILED
                    ? messageText(error, "Agent failed")
                    : entry.output;
            notifications.add(new TaskNotification(entry.request.taskId(), entry.name, entry.request.type(),
                    status, notificationOutput));
            listener = notificationListener;
        }
        notifyListener(listener);
    }

    private static Task snapshot(TaskEntry entry) {
        return new Task(entry.request.taskId(), entry.name, entry.request.type(), entry.status,
                entry.output, entry.error);
    }

    private static String taskName(AgentTaskRequest request) {
        return externalType(request.type()) + ": " + truncate(request.prompt(), 50);
    }

    private static String externalType(AgentType type) {
        return switch (type) {
            case EXPLORE -> "explore";
            case PLAN -> "plan";
            case GENERAL_PURPOSE -> "general-purpose";
        };
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...";
    }

    private static String message(Throwable throwable, String fallback) {
        return messageText(throwable.getMessage(), fallback);
    }

    private static String messageText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void notifyListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // 通知回调只用于唤醒父 Agent，不能改变子任务终态。
        }
    }

    private synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Sub-agent task manager is closed");
        }
    }

    private static final class TaskEntry {
        private final AgentTaskRequest request;
        private final String name;
        private final CancellationToken cancellationToken = CancellationToken.create();
        private AgentTaskStatus status = AgentTaskStatus.PENDING;
        private String output = "";
        private String error;
        private Thread thread;

        private TaskEntry(AgentTaskRequest request, String name) {
            this.request = request;
            this.name = name;
        }
    }

    private static final class TaskRunner implements Runnable {
        private final SubAgentTaskManager taskManager;
        private final TaskEntry entry;

        private TaskRunner(SubAgentTaskManager taskManager, TaskEntry entry) {
            this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        @Override
        public void run() {
            taskManager.run(entry);
        }
    }

    private static final class NoNotificationListener implements Runnable {
        @Override
        public void run() {
            // No listener has been registered yet, or the manager has already closed.
        }
    }
}
