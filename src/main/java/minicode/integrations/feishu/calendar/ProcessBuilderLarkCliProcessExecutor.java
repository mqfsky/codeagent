package minicode.integrations.feishu.calendar;

import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Starts lark-cli directly through {@link ProcessBuilder}; no shell is involved.
 */
public final class ProcessBuilderLarkCliProcessExecutor implements LarkCliProcessExecutor {
    static final int MAX_CAPTURE_BYTES = 1_048_576;

    private static final int READ_BUFFER_BYTES = 8_192;
    private static final long CANCELLATION_POLL_MILLIS = 50;
    private static final long TERMINATION_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    @Override
    public LarkCliProcessResult execute(List<String> argv,
                                        String stdin,
                                        Duration timeout,
                                        CancellationToken cancellationToken)
            throws IOException, InterruptedException, TimeoutException {
        List<String> actualArgv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        if (actualArgv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        actualArgv.forEach(argument -> Objects.requireNonNull(argument, "argv argument"));
        String actualStdin = Objects.requireNonNull(stdin, "stdin");
        Deadline deadline = Deadline.start(requirePositive(timeout));
        CancellationToken actualCancellationToken =
                Objects.requireNonNull(cancellationToken, "cancellationToken");
        actualCancellationToken.throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);

        ProcessBuilder processBuilder = new ProcessBuilder(actualArgv);
        removeModelCredentials(processBuilder.environment());
        Process process = processBuilder.start();
        Set<ProcessHandle> knownDescendants = new LinkedHashSet<>();
        ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong remainingOutputBytes = new AtomicLong(MAX_CAPTURE_BYTES);
        Future<Void> stdinWriter = ioExecutor.submit(
                () -> write(process.getOutputStream(), actualStdin)
        );
        Future<String> stdout = ioExecutor.submit(
                () -> read(process.getInputStream(), remainingOutputBytes)
        );
        Future<String> stderr = ioExecutor.submit(
                () -> read(process.getErrorStream(), remainingOutputBytes)
        );
        boolean completed = false;
        try {
            awaitExit(
                    process,
                    stdinWriter,
                    stdout,
                    stderr,
                    deadline,
                    actualCancellationToken,
                    knownDescendants
            );
            awaitFuture(stdinWriter, deadline, "Failed to send lark-cli input");
            LarkCliProcessResult result = new LarkCliProcessResult(
                    process.exitValue(),
                    awaitFuture(stdout, deadline, "Failed to capture lark-cli output"),
                    awaitFuture(stderr, deadline, "Failed to capture lark-cli output")
            );
            completed = true;
            return result;
        } finally {
            if (!completed) {
                cleanupFailedExecution(
                        process,
                        knownDescendants,
                        List.of(stdinWriter, stdout, stderr)
                );
            }
            ioExecutor.shutdownNow();
        }
    }

    private static void awaitExit(Process process,
                                  Future<Void> stdinWriter,
                                  Future<String> stdout,
                                  Future<String> stderr,
                                  Deadline deadline,
                                  CancellationToken cancellationToken,
                                  Set<ProcessHandle> knownDescendants)
            throws IOException, InterruptedException, TimeoutException {
        while (process.isAlive()) {
            rememberDescendants(process, knownDescendants);
            throwIfFailed(stdinWriter, "Failed to send lark-cli input");
            throwIfFailed(stdout, "Failed to capture lark-cli output");
            throwIfFailed(stderr, "Failed to capture lark-cli output");

            if (cancellationToken.isCancellationRequested()) {
                if (!process.isAlive()) {
                    break;
                }
                try {
                    cancellationToken.throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                } catch (CancellationRequestedException exception) {
                    if (!process.isAlive()) {
                        break;
                    }
                    throw exception;
                }
            }

            long remainingNanos = deadline.remainingNanos();
            if (remainingNanos <= 0) {
                throw timeout();
            }
            long waitMillis = Math.min(
                    CANCELLATION_POLL_MILLIS,
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
            );
            process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        }
        rememberDescendants(process, knownDescendants);
    }

    private static Void write(OutputStream stream, String stdin) throws IOException {
        try (stream) {
            stream.write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    private static String read(InputStream stream, AtomicLong remainingOutputBytes) throws IOException {
        try (stream; ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                claimOutputBytes(remainingOutputBytes, read);
                captured.write(buffer, 0, read);
            }
            return captured.toString(StandardCharsets.UTF_8);
        }
    }

    private static void claimOutputBytes(AtomicLong remainingOutputBytes, int count)
            throws OutputLimitExceededException {
        while (true) {
            long remaining = remainingOutputBytes.get();
            if (count > remaining) {
                throw new OutputLimitExceededException();
            }
            if (remainingOutputBytes.compareAndSet(remaining, remaining - count)) {
                return;
            }
        }
    }

    private static void throwIfFailed(Future<?> future, String failureMessage)
            throws IOException, InterruptedException {
        if (!future.isDone()) {
            return;
        }
        try {
            future.get();
        } catch (ExecutionException exception) {
            throw sanitizedFutureFailure(exception.getCause(), failureMessage);
        } catch (CancellationException exception) {
            throw new IOException(failureMessage);
        }
    }

    private static <T> T awaitFuture(Future<T> future,
                                     Deadline deadline,
                                     String failureMessage)
            throws IOException, InterruptedException, TimeoutException {
        try {
            if (future.isDone()) {
                return future.get();
            }
            long remainingNanos = deadline.remainingNanos();
            if (remainingNanos <= 0) {
                throw timeout();
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            throw sanitizedFutureFailure(exception.getCause(), failureMessage);
        } catch (CancellationException exception) {
            throw new IOException(failureMessage);
        } catch (TimeoutException exception) {
            throw timeout();
        }
    }

    private static IOException sanitizedFutureFailure(Throwable cause, String failureMessage) {
        if (cause instanceof OutputLimitExceededException) {
            return new IOException("lark-cli output exceeded the capture limit");
        }
        return new IOException(failureMessage);
    }

    private static void cleanupFailedExecution(Process process,
                                               Set<ProcessHandle> knownDescendants,
                                               List<? extends Future<?>> futures) {
        rememberDescendants(process, knownDescendants);
        closeProcessStreams(process);
        futures.forEach(future -> future.cancel(true));
        terminateProcessTree(process, knownDescendants);
        closeProcessStreams(process);
    }

    private static void terminateProcessTree(Process process, Set<ProcessHandle> knownDescendants) {
        ProcessHandle root = process.toHandle();
        rememberDescendants(process, knownDescendants);
        destroyAll(knownDescendants, false);
        destroy(root, false);

        long stopWaitingAt = System.nanoTime() + TERMINATION_GRACE_NANOS;
        while (treeIsAlive(root, knownDescendants) && System.nanoTime() - stopWaitingAt < 0) {
            rememberDescendants(process, knownDescendants);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }

        rememberDescendants(process, knownDescendants);
        destroyAll(knownDescendants, true);
        destroy(root, true);
    }

    private static void rememberDescendants(Process process, Set<ProcessHandle> knownDescendants) {
        rememberDescendants(process.toHandle(), knownDescendants);
        List<ProcessHandle> knownSnapshot = new ArrayList<>(knownDescendants);
        knownSnapshot.forEach(handle -> rememberDescendants(handle, knownDescendants));
    }

    private static void rememberDescendants(ProcessHandle ancestor,
                                            Set<ProcessHandle> knownDescendants) {
        try (var descendants = ancestor.descendants()) {
            descendants.forEach(knownDescendants::add);
        } catch (RuntimeException ignored) {
            // Cleanup still terminates the root process when descendants cannot be enumerated.
        }
    }

    private static void destroyAll(Set<ProcessHandle> handles, boolean forcibly) {
        List<ProcessHandle> snapshot = new ArrayList<>(handles);
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            destroy(snapshot.get(index), forcibly);
        }
    }

    private static void destroy(ProcessHandle handle, boolean forcibly) {
        if (!handle.isAlive()) {
            return;
        }
        try {
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (RuntimeException ignored) {
            // Continue best-effort cleanup for the remaining process handles.
        }
    }

    private static boolean treeIsAlive(ProcessHandle root, Set<ProcessHandle> knownDescendants) {
        if (root.isAlive()) {
            return true;
        }
        return knownDescendants.stream().anyMatch(ProcessHandle::isAlive);
    }

    private static void closeProcessStreams(Process process) {
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Cleanup must not replace the original sanitized failure.
        }
    }

    private static TimeoutException timeout() {
        return new TimeoutException("lark-cli process timed out");
    }

    static void removeModelCredentials(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        environment.remove("ANTHROPIC_API_KEY");
        environment.remove("ANTHROPIC_AUTH_TOKEN");
    }

    private static Duration requirePositive(Duration timeout) {
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    private record Deadline(long startedAtNanos, long timeoutNanos) {
        private static Deadline start(Duration timeout) {
            long timeoutNanos;
            try {
                timeoutNanos = timeout.toNanos();
            } catch (ArithmeticException exception) {
                timeoutNanos = Long.MAX_VALUE;
            }
            return new Deadline(System.nanoTime(), timeoutNanos);
        }

        private long remainingNanos() {
            long elapsedNanos = System.nanoTime() - startedAtNanos;
            return elapsedNanos >= timeoutNanos ? 0 : timeoutNanos - elapsedNanos;
        }
    }

    private static final class OutputLimitExceededException extends IOException {
    }
}
