package minicode.integrations.feishu.calendar;

import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessBuilderLarkCliProcessExecutorTest {
    private final ProcessBuilderLarkCliProcessExecutor executor = new ProcessBuilderLarkCliProcessExecutor();

    @Test
    void passesExactArgvAndStdinAndCapturesBothOutputStreams() throws Exception {
        LarkCliProcessResult result = executor.execute(
                List.of(
                        Path.of("/bin/sh").toString(),
                        "-c",
                        "read value; printf 'out:%s' \"$value\"; printf 'err:%s' \"$value\" >&2",
                        "ignored-shell-zero"
                ),
                "payload with spaces;$(not-executed)\n",
                Duration.ofSeconds(2),
                CancellationToken.none()
        );

        assertEquals(0, result.exitCode());
        assertEquals("out:payload with spaces;$(not-executed)", result.stdout());
        assertEquals("err:payload with spaces;$(not-executed)", result.stderr());
    }

    @Test
    void timesOutAndTerminatesRunningProcess() {
        long startedAt = System.nanoTime();

        assertThrows(TimeoutException.class, () -> executor.execute(
                List.of("/bin/sh", "-c", "trap '' TERM; while :; do :; done"),
                "",
                Duration.ofMillis(50),
                CancellationToken.none()
        ));

        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(2)) < 0);
    }

    @Test
    void rejectsAlreadyCancelledExecutionBeforeStartingProcess() {
        CancellationToken token = CancellationToken.cancelled(CancellationSource.USER, "stop");

        CancellationRequestedException error = assertThrows(
                CancellationRequestedException.class,
                () -> executor.execute(
                        List.of("/path/that/must/not/be/started"),
                        "",
                        Duration.ofSeconds(1),
                        token
                )
        );

        assertEquals(CancellationPhase.TOOL_EXECUTION, error.cancellation().phase());
    }

    @Test
    void cancellationTerminatesRunningProcess() throws Exception {
        CancellationToken token = CancellationToken.create();
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            token.requestCancellation(CancellationSource.USER, "stop");
        });
        long startedAt = System.nanoTime();

        CancellationRequestedException error = assertThrows(
                CancellationRequestedException.class,
                () -> executor.execute(
                        List.of("/bin/sh", "-c", "trap '' TERM; while :; do :; done"),
                        "",
                        Duration.ofSeconds(5),
                        token
                )
        );
        canceller.join();

        assertEquals(CancellationPhase.TOOL_EXECUTION, error.cancellation().phase());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(2)) < 0);
    }

    @Test
    void rejectsOutputBeyondCombinedCaptureLimitWithoutRetainingRawOutput() {
        long startedAt = System.nanoTime();

        IOException error = assertThrows(IOException.class, () -> executor.execute(
                List.of("/usr/bin/yes", "sensitive-output-that-must-not-appear"),
                "",
                Duration.ofSeconds(5),
                CancellationToken.none()
        ));

        assertEquals("lark-cli output exceeded the capture limit", error.getMessage());
        assertFalse(error.getMessage().contains("sensitive-output"), error.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(2)) < 0);
    }

    @Test
    void timeoutIncludesOutputCollectionAfterRootProcessExits() {
        long startedAt = System.nanoTime();

        assertThrows(TimeoutException.class, () -> executor.execute(
                List.of("/bin/sh", "-c", "sleep 1 & sleep 0.2; exit 0"),
                "",
                Duration.ofMillis(500),
                CancellationToken.none()
        ));

        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(2)) < 0);
    }

    @Test
    void removesOnlyModelCredentialsNeededByNeitherLarkNorProcessStartup() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "ANTHROPIC_API_KEY", "api-secret",
                "ANTHROPIC_AUTH_TOKEN", "auth-secret",
                "HOME", "/home/test-user",
                "PATH", "/usr/bin:/bin",
                "LARK_ACCESS_TOKEN", "lark-secret"
        ));

        ProcessBuilderLarkCliProcessExecutor.removeModelCredentials(environment);

        assertFalse(environment.containsKey("ANTHROPIC_API_KEY"));
        assertFalse(environment.containsKey("ANTHROPIC_AUTH_TOKEN"));
        assertEquals("/home/test-user", environment.get("HOME"));
        assertEquals("/usr/bin:/bin", environment.get("PATH"));
        assertEquals("lark-secret", environment.get("LARK_ACCESS_TOKEN"));
    }
}
