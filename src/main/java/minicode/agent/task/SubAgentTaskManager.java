package minicode.agent.task;

import minicode.agent.event.AgentTaskEvent;
import minicode.agent.event.AgentTaskEventSink;
import minicode.agent.model.AgentRunMode;
import minicode.agent.model.AgentRunResult;
import minicode.agent.model.AgentTaskRequest;
import minicode.agent.model.AgentTaskSnapshot;
import minicode.agent.model.AgentTaskStatus;
import minicode.agent.model.AgentType;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 协调支持持久化、并发限制和取消的后台子 Agent 任务。
 */
public final class SubAgentTaskManager implements AutoCloseable {
    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final int DEFAULT_MAX_QUEUED = 16;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private final AgentTaskStore store;
    private final AgentTaskExecutor taskExecutor;
    private final AgentTaskEventSink eventSink;
    private final Clock clock;
    private final Duration taskTimeout;
    private final Semaphore executionSlots;
    private final Semaphore capacity;
    private final ExecutorService workers;
    private final ScheduledExecutorService timeoutScheduler;
    private final ConcurrentMap<String, TaskControl> controls = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public SubAgentTaskManager(AgentTaskStore store,
                               AgentInbox inbox,
                               AgentTaskExecutor taskExecutor) {
        this(store, inbox, taskExecutor, AgentTaskEventSink.noOp());
    }

    public SubAgentTaskManager(AgentTaskStore store,
                               AgentInbox inbox,
                               AgentTaskExecutor taskExecutor,
                               AgentTaskEventSink eventSink) {
        this(store, inbox, taskExecutor, eventSink, Clock.systemUTC(), DEFAULT_TIMEOUT,
                DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_QUEUED);
    }

    public SubAgentTaskManager(AgentTaskStore store,
                               AgentInbox inbox,
                               AgentTaskExecutor taskExecutor,
                               AgentTaskEventSink eventSink,
                               Clock clock,
                               Duration taskTimeout,
                               int maxConcurrent,
                               int maxQueued) {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(inbox, "inbox");
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.taskTimeout = Objects.requireNonNull(taskTimeout, "taskTimeout");
        if (taskTimeout.isZero() || taskTimeout.isNegative()) {
            throw new IllegalArgumentException("taskTimeout must be positive");
        }
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive");
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("maxQueued must not be negative");
        }
        this.executionSlots = new Semaphore(maxConcurrent, true);
        this.capacity = new Semaphore(Math.addExact(maxConcurrent, maxQueued), true);
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sub-agent-timeouts");
            thread.setDaemon(true);
            return thread;
        });
    }

    public AgentTaskSnapshot submit(AgentTaskRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.runMode() != AgentRunMode.BACKGROUND) {
            throw new IllegalArgumentException("SubAgentTaskManager only accepts BACKGROUND tasks");
        }
        if (request.type() == AgentType.GENERAL_PURPOSE) {
            throw new IllegalArgumentException("GENERAL_PURPOSE agents cannot run in background");
        }

        synchronized (lifecycleLock) {
            ensureOpen();
            if (!capacity.tryAcquire()) {
                throw new AgentTaskRejectedException("Background agent capacity exceeded");
            }
            if (store.find(request.taskId(), request.cwd(), request.parentSessionId()).isPresent()) {
                capacity.release();
                throw new IllegalArgumentException("Task already exists: " + request.taskId());
            }

            AgentTaskSnapshot queued = AgentTaskSnapshot.queued(request);
            TaskControl control = new TaskControl(request, queued);
            if (controls.putIfAbsent(request.taskId(), control) != null) {
                capacity.release();
                throw new IllegalArgumentException("Task already exists: " + request.taskId());
            }

            try {
                store.save(queued);
                publish(new AgentTaskEvent.StateChangedEvent(request.agentId(), Optional.of(request.taskId()),
                        request.parentTurnId(), request.type(), now(), Optional.empty(), AgentTaskStatus.QUEUED));
                Future<?> future = workers.submit(() -> run(control));
                control.future.set(future);
                if (control.snapshot.get().status().isTerminal()) {
                    future.cancel(true);
                }
                return queued;
            } catch (RejectedExecutionException exception) {
                transition(control, AgentTaskStatus.INTERRUPTED, Optional.empty(),
                        Optional.of("Task manager rejected execution"));
                controls.remove(request.taskId(), control);
                releaseCapacity(control);
                throw new AgentTaskRejectedException("Background task executor is closed");
            } catch (RuntimeException exception) {
                controls.remove(request.taskId(), control);
                releaseCapacity(control);
                throw exception;
            }
        }
    }

    public Optional<AgentTaskSnapshot> find(String taskId, String cwd, String parentSessionId) {
        Objects.requireNonNull(taskId, "taskId");
        TaskControl active = controls.get(taskId);
        if (active != null) {
            AgentTaskSnapshot snapshot = active.snapshot.get();
            if (snapshot.cwd().equals(cwd) && snapshot.parentSessionId().equals(parentSessionId)) {
                return Optional.of(snapshot);
            }
            return Optional.empty();
        }
        return store.find(taskId, cwd, parentSessionId);
    }

    public List<AgentTaskSnapshot> list(String cwd, String parentSessionId, int limit) {
        return store.list(cwd, parentSessionId, limit);
    }

    public List<AgentTaskSnapshot> recover(String cwd, String parentSessionId) {
        List<AgentTaskSnapshot> recovered = store.recover(cwd, parentSessionId, now());
        publishRecovered(recovered);
        return recovered;
    }

    /**
     * 恢复磁盘上的所有遗留任务，但只为当前作用域发布生命周期事件。
     * 这样既能完整执行启动清理，又不会把其他 Session 泄漏到当前 Renderer。
     */
    public void recoverPersistedTasks(String eventCwd, String eventParentSessionId) {
        List<AgentTaskSnapshot> recovered = store.recoverAll(now());
        publishRecovered(recovered.stream()
                .filter(snapshot -> snapshot.cwd().equals(eventCwd)
                        && snapshot.parentSessionId().equals(eventParentSessionId))
                .toList());
    }

    private void publishRecovered(List<AgentTaskSnapshot> recovered) {
        for (AgentTaskSnapshot snapshot : recovered) {
            AgentTaskStatus previous = snapshot.startedAt().isPresent()
                    ? AgentTaskStatus.RUNNING
                    : AgentTaskStatus.QUEUED;
            publishState(snapshot, Optional.of(previous));
        }
    }

    public CancelResult cancel(String taskId,
                               String cwd,
                               String parentSessionId,
                               String reason) {
        Objects.requireNonNull(taskId, "taskId");
        reason = requireText(reason, "reason");
        synchronized (lifecycleLock) {
            TaskControl control = controls.get(taskId);
            if (control == null) {
                Optional<AgentTaskSnapshot> stored = store.find(taskId, cwd, parentSessionId);
                if (stored.isEmpty()) {
                    throw new IllegalArgumentException("Task not found");
                }
                AgentTaskSnapshot snapshot = stored.orElseThrow();
                if (snapshot.status().isTerminal()) {
                    return new CancelResult(snapshot, false);
                }
                // 没有本地控制句柄的非终态快照可能属于其他存活进程。
                // v1 不支持跨进程取消，因此绝不能覆盖该任务的生命周期。
                throw new IllegalArgumentException("Task not found");
            }

            AgentTaskSnapshot scoped = control.snapshot.get();
            if (!scoped.cwd().equals(cwd) || !scoped.parentSessionId().equals(parentSessionId)) {
                throw new IllegalArgumentException("Task not found");
            }
            Optional<AgentTaskSnapshot> changed = transition(control, AgentTaskStatus.CANCELLED,
                    scoped.output(), Optional.of(reason));
            if (changed.isPresent()) {
                signalCancellation(control, CancellationSource.USER, reason);
                controls.remove(taskId, control);
                releaseCapacity(control);
            }
            AgentTaskSnapshot latest = changed.orElseGet(() -> control.snapshot.get());
            return new CancelResult(latest, changed.isPresent());
        }
    }

    public CancelResult cancel(String taskId, String cwd, String parentSessionId) {
        return cancel(taskId, cwd, parentSessionId, "Cancelled by parent agent");
    }

    @Override
    public void close() {
        RuntimeException persistenceFailure = null;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            for (TaskControl control : List.copyOf(controls.values())) {
                String reason = "Task manager is shutting down";
                try {
                    transition(control, AgentTaskStatus.INTERRUPTED,
                            control.snapshot.get().output(), Optional.of(reason));
                } catch (RuntimeException exception) {
                    if (persistenceFailure == null) {
                        persistenceFailure = exception;
                    } else {
                        persistenceFailure.addSuppressed(exception);
                    }
                } finally {
                    // 持久化失败时，也不能让子 Agent 在 MCP 关闭期间继续执行。
                    signalCancellation(control, CancellationSource.SYSTEM, reason);
                    controls.remove(control.request.taskId(), control);
                    releaseCapacity(control);
                }
            }
            timeoutScheduler.shutdownNow();
            workers.shutdownNow();
        }
        if (!awaitTermination(timeoutScheduler, Duration.ofSeconds(1))) {
            persistenceFailure = appendFailure(persistenceFailure,
                    new IllegalStateException("Task timeout scheduler did not terminate"));
        }
        if (!awaitTermination(workers, Duration.ofSeconds(5))) {
            persistenceFailure = appendFailure(persistenceFailure,
                    new IllegalStateException("Background agent workers did not terminate"));
        }
        if (persistenceFailure != null) {
            throw persistenceFailure;
        }
    }

    private void run(TaskControl control) {
        boolean acquiredExecutionSlot = false;
        try {
            control.runner.set(Thread.currentThread());
            executionSlots.acquire();
            acquiredExecutionSlot = true;
            if (control.snapshot.get().status().isTerminal()) {
                return;
            }
            if (control.cancellationToken.isCancellationRequested()) {
                transition(control, AgentTaskStatus.CANCELLED, Optional.empty(),
                        Optional.of("Task was cancelled before execution"));
                return;
            }
            if (transition(control, AgentTaskStatus.RUNNING, Optional.empty(), Optional.empty()).isEmpty()) {
                return;
            }
            control.timeoutFuture.set(timeoutScheduler.schedule(() -> timeOut(control),
                    taskTimeout.toMillis(), TimeUnit.MILLISECONDS));

            AgentRunResult result = Objects.requireNonNull(
                    taskExecutor.execute(control.request, control.cancellationToken),
                    "taskExecutor result");
            if (result.cancelled()) {
                transition(control, AgentTaskStatus.CANCELLED, optionalOutput(result.output()),
                        result.error().or(() -> Optional.of("Child agent was cancelled")));
            } else if (result.error().isPresent()) {
                transition(control, AgentTaskStatus.FAILED, optionalOutput(result.output()), result.error());
            } else {
                transition(control, AgentTaskStatus.COMPLETED, optionalOutput(result.output()), Optional.empty());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!control.snapshot.get().status().isTerminal()) {
                AgentTaskStatus status = control.cancellationToken.isCancellationRequested()
                        ? AgentTaskStatus.CANCELLED
                        : AgentTaskStatus.INTERRUPTED;
                transition(control, status, control.snapshot.get().output(),
                        Optional.of(exceptionMessage(exception, "Task thread was interrupted")));
            }
        } catch (CancellationRequestedException | CancellationException exception) {
            transition(control, AgentTaskStatus.CANCELLED, control.snapshot.get().output(),
                    Optional.of(exceptionMessage(exception, "Task was cancelled")));
        } catch (Throwable throwable) {
            if (!control.snapshot.get().status().isTerminal()) {
                transition(control, AgentTaskStatus.FAILED, control.snapshot.get().output(),
                        Optional.of(exceptionMessage(throwable, throwable.getClass().getSimpleName())));
            }
        } finally {
            ScheduledFuture<?> timeout = control.timeoutFuture.getAndSet(null);
            if (timeout != null) {
                timeout.cancel(false);
            }
            control.runner.set(null);
            if (acquiredExecutionSlot) {
                executionSlots.release();
            }
            releaseCapacity(control);
            if (control.snapshot.get().status().isTerminal()) {
                controls.remove(control.request.taskId(), control);
            }
        }
    }

    private void timeOut(TaskControl control) {
        String reason = "Background agent exceeded " + taskTimeout;
        Optional<AgentTaskSnapshot> changed = transition(control, AgentTaskStatus.TIMED_OUT,
                control.snapshot.get().output(), Optional.of(reason));
        if (changed.isPresent()) {
            signalCancellation(control, CancellationSource.SYSTEM, reason);
        }
    }

    private Optional<AgentTaskSnapshot> transition(TaskControl control,
                                                   AgentTaskStatus target,
                                                   Optional<String> output,
                                                   Optional<String> error) {
        synchronized (control.transitionLock) {
            AgentTaskSnapshot previous = control.snapshot.get();
            if (!previous.status().canTransitionTo(target)) {
                return Optional.empty();
            }
            AgentTaskSnapshot next = previous.transitionTo(target, now(), output, error);
            store.save(next);
            if (!control.snapshot.compareAndSet(previous, next)) {
                throw new IllegalStateException("Task state changed while holding its transition lock");
            }
            publishState(next, Optional.of(previous.status()));
            return Optional.of(next);
        }
    }

    private static void signalCancellation(TaskControl control, CancellationSource source, String reason) {
        control.cancellationToken.requestCancellation(source, reason);
        Future<?> future = control.future.get();
        if (future != null) {
            future.cancel(true);
        }
        Thread runner = control.runner.get();
        if (runner != null) {
            runner.interrupt();
        }
    }

    private static boolean awaitTermination(ExecutorService executor, Duration timeout) {
        try {
            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    private static RuntimeException appendFailure(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private void publishState(AgentTaskSnapshot snapshot, Optional<AgentTaskStatus> previous) {
        publish(new AgentTaskEvent.StateChangedEvent(snapshot.agentId(), Optional.of(snapshot.taskId()),
                snapshot.parentTurnId(), snapshot.type(), now(), previous, snapshot.status()));
    }

    private void publish(AgentTaskEvent event) {
        try {
            eventSink.onEvent(event);
        } catch (RuntimeException ignored) {
            // 可观测性逻辑绝不能改变任务生命周期语义。
        }
    }

    private void releaseCapacity(TaskControl control) {
        if (control.capacityReleased.compareAndSet(false, true)) {
            capacity.release();
        }
    }

    private Instant now() {
        return clock.instant();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new AgentTaskRejectedException("Task manager is closed");
        }
    }

    private static Optional<String> optionalOutput(String output) {
        return output.isEmpty() ? Optional.empty() : Optional.of(output);
    }

    private static String exceptionMessage(Throwable throwable, String fallback) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class TaskControl {
        private final AgentTaskRequest request;
        private final CancellationToken cancellationToken = CancellationToken.create();
        private final AtomicReference<AgentTaskSnapshot> snapshot;
        private final AtomicReference<Future<?>> future = new AtomicReference<>();
        private final AtomicReference<Thread> runner = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timeoutFuture = new AtomicReference<>();
        private final AtomicBoolean capacityReleased = new AtomicBoolean();
        private final Object transitionLock = new Object();

        private TaskControl(AgentTaskRequest request, AgentTaskSnapshot snapshot) {
            this.request = request;
            this.snapshot = new AtomicReference<>(snapshot);
        }
    }
}
