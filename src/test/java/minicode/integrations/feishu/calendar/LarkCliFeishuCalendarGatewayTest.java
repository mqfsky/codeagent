package minicode.integrations.feishu.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationSource;
import minicode.core.turn.CancellationToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LarkCliFeishuCalendarGatewayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CLI_PATH = Path.of("/opt/homebrew/bin/lark-cli");
    private static final Duration TIMEOUT = Duration.ofSeconds(12);

    @Test
    void createsPrivateBusyEventInPrimaryUserCalendarWithExactArgvAndStdin() throws Exception {
        RecordingProcessExecutor executor = new RecordingProcessExecutor(
                successful("""
                        {"data":{"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}}
                        """),
                successful("""
                        {"data":{"event":{"event_id":"event-123","app_link":"https://example.test/event-123"}}}
                        """)
        );
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);
        ZonedDateTime start = ZonedDateTime.of(
                2026, 7, 25, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai")
        );
        FeishuCalendarCreateRequest request = new FeishuCalendarCreateRequest(
                "Project sync",
                start,
                start.plusMinutes(45),
                10,
                Optional.of("Discuss token=do-not-log")
        );
        CancellationToken token = CancellationToken.none();

        FeishuCalendarCreateResult result = gateway.create(request, "idem-secret-123", token);

        assertEquals("event-123", result.eventId());
        assertEquals(Optional.of("https://example.test/event-123"), result.appLink());
        assertEquals(2, executor.calls.size());

        RecordedCall primaryCall = executor.calls.get(0);
        assertEquals(List.of(
                CLI_PATH.toString(),
                "calendar", "calendars", "primary",
                "--as", "user",
                "--format", "json"
        ), primaryCall.argv());
        assertEquals("", primaryCall.stdin());
        assertEquals(TIMEOUT, primaryCall.timeout());
        assertEquals(token, primaryCall.cancellationToken());

        RecordedCall createCall = executor.calls.get(1);
        assertEquals(List.of(
                CLI_PATH.toString(),
                "calendar", "events", "create",
                "--as", "user",
                "--calendar-id", "primary-calendar",
                "--idempotency-key", "idem-secret-123",
                "--data", "-",
                "--format", "json"
        ), createCall.argv());
        JsonNode payload = JSON.readTree(createCall.stdin());
        assertEquals("Project sync", payload.path("summary").asText());
        assertEquals("Discuss token=do-not-log", payload.path("description").asText());
        assertEquals(Long.toString(start.toEpochSecond()), payload.path("start_time").path("timestamp").asText());
        assertEquals("Asia/Shanghai", payload.path("start_time").path("timezone").asText());
        assertEquals(Long.toString(start.plusMinutes(45).toEpochSecond()),
                payload.path("end_time").path("timestamp").asText());
        assertEquals("Asia/Shanghai", payload.path("end_time").path("timezone").asText());
        assertEquals(10, payload.path("reminders").get(0).path("minutes").asInt());
        assertEquals("private", payload.path("visibility").asText());
        assertEquals("busy", payload.path("free_busy_status").asText());
        assertEquals("no_meeting", payload.path("vchat").path("vc_type").asText());
        assertEquals(token, createCall.cancellationToken());
    }

    @Test
    void omitsDescriptionAndAcceptsDirectCliOutputShape() throws Exception {
        RecordingProcessExecutor executor = new RecordingProcessExecutor(
                successful("""
                        {"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}
                        """),
                successful("""
                        {"event":{"event_id":"event-123","app_link":""}}
                        """)
        );
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarCreateResult result = gateway.create(
                request(Optional.empty()),
                "idem-123",
                CancellationToken.none()
        );

        assertEquals(Optional.empty(), result.appLink());
        JsonNode payload = JSON.readTree(executor.calls.get(1).stdin());
        assertFalse(payload.has("description"));
    }

    @Test
    void stopsAfterPrimaryLookupFailureWithoutBotFallbackAndRedactsCliOutput() {
        RecordingProcessExecutor executor = new RecordingProcessExecutor(
                new LarkCliProcessResult(
                        7,
                        "{\"access_token\":\"stdout-secret\"}",
                        "Authorization: Bearer stderr-secret"
                )
        );
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarGatewayException error = assertThrows(
                FeishuCalendarGatewayException.class,
                () -> gateway.create(request(Optional.of("description-secret")),
                        "idempotency-secret", CancellationToken.none())
        );

        assertEquals(1, executor.calls.size());
        assertTrue(executor.calls.getFirst().argv().contains("user"));
        assertFalse(executor.calls.getFirst().argv().contains("bot"));
        assertTrue(error.getMessage().contains("exit code 7"), error.getMessage());
        assertRedacted(error);
    }

    @Test
    void redactsCreateFailureWithoutRetryingAsBot() {
        RecordingProcessExecutor executor = new RecordingProcessExecutor(
                successful("""
                        {"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}
                        """),
                new LarkCliProcessResult(
                        1,
                        "{\"description\":\"description-secret\"}",
                        "idempotency-secret access_token=cli-secret"
                )
        );
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarGatewayException error = assertThrows(
                FeishuCalendarGatewayException.class,
                () -> gateway.create(request(Optional.of("description-secret")),
                        "idempotency-secret", CancellationToken.none())
        );

        assertEquals(2, executor.calls.size());
        assertTrue(executor.calls.stream().allMatch(call -> call.argv().contains("user")));
        assertTrue(executor.calls.stream().noneMatch(call -> call.argv().contains("bot")));
        assertRedacted(error);
    }

    @Test
    void malformedResponseDoesNotExposeReturnedContent() {
        RecordingProcessExecutor executor = new RecordingProcessExecutor(
                successful("{access_token:response-secret")
        );
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarGatewayException error = assertThrows(
                FeishuCalendarGatewayException.class,
                () -> gateway.create(request(Optional.empty()), "idem-123", CancellationToken.none())
        );

        assertTrue(error.getMessage().contains("invalid primary calendar response"));
        assertFalse(error.getMessage().contains("response-secret"), error.getMessage());
    }

    @Test
    void redactsProcessExceptionDetails() {
        LarkCliProcessExecutor executor = (argv, stdin, timeout, cancellationToken) -> {
            throw new IOException("access_token=io-secret");
        };
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarGatewayException error = assertThrows(
                FeishuCalendarGatewayException.class,
                () -> gateway.create(request(Optional.empty()), "idem-123", CancellationToken.none())
        );

        assertFalse(throwableMessages(error).contains("io-secret"), throwableMessages(error));
    }

    @Test
    void retriesCreateTimeoutOnceWithIdenticalIdempotentInvocation() {
        List<RecordedCall> calls = new ArrayList<>();
        AtomicInteger invocation = new AtomicInteger();
        LarkCliProcessExecutor executor = (argv, stdin, timeout, cancellationToken) -> {
            calls.add(new RecordedCall(List.copyOf(argv), stdin, timeout, cancellationToken));
            return switch (invocation.incrementAndGet()) {
                case 1 -> successful("""
                        {"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}
                        """);
                case 2 -> throw new TimeoutException("unknown create result");
                case 3 -> successful("""
                        {"event":{"event_id":"event-after-retry"}}
                        """);
                default -> throw new AssertionError("Unexpected process invocation");
            };
        };
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarCreateResult result =
                gateway.create(request(Optional.of("description")), "idem-retry", CancellationToken.none());

        assertEquals("event-after-retry", result.eventId());
        assertEquals(3, calls.size());
        assertEquals(calls.get(1).argv(), calls.get(2).argv());
        assertEquals(calls.get(1).stdin(), calls.get(2).stdin());
        assertTrue(calls.get(1).argv().contains("idem-retry"));
    }

    @Test
    void retriesCreateTimeoutAtMostOnce() {
        AtomicInteger invocation = new AtomicInteger();
        LarkCliProcessExecutor executor = (argv, stdin, timeout, cancellationToken) -> {
            if (invocation.incrementAndGet() == 1) {
                return successful("""
                        {"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}
                        """);
            }
            throw new TimeoutException("access_token=timeout-secret");
        };
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarGatewayException error = assertThrows(
                FeishuCalendarGatewayException.class,
                () -> gateway.create(request(Optional.empty()), "idem-retry", CancellationToken.none())
        );

        assertEquals(3, invocation.get());
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        assertFalse(throwableMessages(error).contains("timeout-secret"), throwableMessages(error));
    }

    @Test
    void doesNotRetryPrimaryCalendarTimeout() {
        AtomicInteger invocation = new AtomicInteger();
        LarkCliProcessExecutor executor = (argv, stdin, timeout, cancellationToken) -> {
            invocation.incrementAndGet();
            throw new TimeoutException("unknown primary result");
        };
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        assertThrows(
                FeishuCalendarGatewayException.class,
                () -> gateway.create(request(Optional.empty()), "idem-retry", CancellationToken.none())
        );

        assertEquals(1, invocation.get());
    }

    @Test
    void doesNotInvokeProcessExecutorWhenAlreadyCancelled() {
        RecordingProcessExecutor executor = new RecordingProcessExecutor();
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);
        CancellationToken token = CancellationToken.cancelled(CancellationSource.USER, "stop");

        assertThrows(
                CancellationRequestedException.class,
                () -> gateway.create(request(Optional.empty()), "idem-123", token)
        );

        assertTrue(executor.calls.isEmpty());
    }

    @Test
    void cancellationAfterPrimarySuccessPreventsCreateInvocation() {
        AtomicInteger invocation = new AtomicInteger();
        CancellationToken token = CancellationToken.create();
        LarkCliProcessExecutor executor = (argv, stdin, timeout, cancellationToken) -> {
            invocation.incrementAndGet();
            token.requestCancellation(CancellationSource.USER, "stop after primary lookup");
            return successful("""
                    {"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}
                    """);
        };
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        assertThrows(
                CancellationRequestedException.class,
                () -> gateway.create(request(Optional.empty()), "idem-123", token)
        );

        assertEquals(1, invocation.get());
    }

    @Test
    void successfulCreateResultWinsOverCancellationArrivingAfterWriteExit() {
        AtomicInteger invocation = new AtomicInteger();
        CancellationToken token = CancellationToken.create();
        LarkCliProcessExecutor executor = (argv, stdin, timeout, cancellationToken) -> {
            return switch (invocation.incrementAndGet()) {
                case 1 -> successful("""
                        {"calendars":[{"calendar":{"calendar_id":"primary-calendar"}}]}
                        """);
                case 2 -> {
                    token.requestCancellation(CancellationSource.USER, "arrived after successful write");
                    yield successful("""
                            {"event":{"event_id":"event-created"}}
                            """);
                }
                default -> throw new AssertionError("Unexpected process invocation");
            };
        };
        LarkCliFeishuCalendarGateway gateway =
                new LarkCliFeishuCalendarGateway(CLI_PATH, TIMEOUT, executor);

        FeishuCalendarCreateResult result =
                gateway.create(request(Optional.empty()), "idem-123", token);

        assertEquals("event-created", result.eventId());
        assertEquals(2, invocation.get());
        assertTrue(token.isCancellationRequested());
    }

    @Test
    void validatesRequestAndGatewayInputs() {
        ZonedDateTime start = ZonedDateTime.now(ZoneId.of("UTC"));

        assertThrows(IllegalArgumentException.class, () -> new FeishuCalendarCreateRequest(
                " ",
                start,
                start.plusMinutes(1),
                5,
                Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new FeishuCalendarCreateRequest(
                "summary",
                start,
                start,
                5,
                Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new FeishuCalendarCreateRequest(
                "summary",
                start,
                start.plusMinutes(1),
                -1,
                Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class,
                () -> new LarkCliFeishuCalendarGateway(CLI_PATH, Duration.ZERO, new RecordingProcessExecutor()));
    }

    private static FeishuCalendarCreateRequest request(Optional<String> description) {
        ZonedDateTime start = ZonedDateTime.of(
                2026, 7, 25, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai")
        );
        return new FeishuCalendarCreateRequest(
                "Project sync",
                start,
                start.plusMinutes(45),
                10,
                description
        );
    }

    private static LarkCliProcessResult successful(String stdout) {
        return new LarkCliProcessResult(0, stdout, "");
    }

    private static void assertRedacted(Throwable error) {
        String allMessages = throwableMessages(error);
        assertFalse(allMessages.contains("stdout-secret"), allMessages);
        assertFalse(allMessages.contains("stderr-secret"), allMessages);
        assertFalse(allMessages.contains("description-secret"), allMessages);
        assertFalse(allMessages.contains("idempotency-secret"), allMessages);
        assertFalse(allMessages.contains("cli-secret"), allMessages);
    }

    private static String throwableMessages(Throwable error) {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static final class RecordingProcessExecutor implements LarkCliProcessExecutor {
        private final Queue<LarkCliProcessResult> results = new ArrayDeque<>();
        private final List<RecordedCall> calls = new ArrayList<>();

        private RecordingProcessExecutor(LarkCliProcessResult... results) {
            this.results.addAll(List.of(results));
        }

        @Override
        public LarkCliProcessResult execute(List<String> argv,
                                            String stdin,
                                            Duration timeout,
                                            CancellationToken cancellationToken) {
            calls.add(new RecordedCall(List.copyOf(argv), stdin, timeout, cancellationToken));
            if (results.isEmpty()) {
                throw new AssertionError("Unexpected process invocation: " + argv);
            }
            return results.remove();
        }
    }

    private record RecordedCall(List<String> argv,
                                String stdin,
                                Duration timeout,
                                CancellationToken cancellationToken) {
    }
}
