package minicode.integrations.feishu.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import minicode.core.turn.CancellationPhase;
import minicode.core.turn.CancellationRequestedException;
import minicode.core.turn.CancellationToken;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Feishu calendar gateway backed exclusively by the authenticated user's lark-cli session.
 */
public final class LarkCliFeishuCalendarGateway implements FeishuCalendarGateway {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cliPath;
    private final Duration timeout;
    private final LarkCliProcessExecutor processExecutor;

    public LarkCliFeishuCalendarGateway(Path cliPath, Duration timeout) {
        this(cliPath, timeout, new ProcessBuilderLarkCliProcessExecutor());
    }

    public LarkCliFeishuCalendarGateway(Path cliPath,
                                        Duration timeout,
                                        LarkCliProcessExecutor processExecutor) {
        this.cliPath = Objects.requireNonNull(cliPath, "cliPath");
        this.timeout = requirePositive(timeout);
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor");
    }

    @Override
    public FeishuCalendarCreateResult create(FeishuCalendarCreateRequest request,
                                             String idempotencyKey,
                                             CancellationToken cancellationToken) {
        FeishuCalendarCreateRequest actualRequest = Objects.requireNonNull(request, "request");
        String actualIdempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        CancellationToken actualCancellationToken =
                Objects.requireNonNull(cancellationToken, "cancellationToken");

        String calendarId = primaryCalendarId(actualCancellationToken);
        LarkCliProcessResult createResult = execute(
                List.of(
                        cliPath.toString(),
                        "calendar", "events", "create",
                        "--as", "user",
                        "--calendar-id", calendarId,
                        "--idempotency-key", actualIdempotencyKey,
                        "--data", "-",
                        "--format", "json"
                ),
                createPayload(actualRequest),
                "creating the Feishu calendar event",
                actualCancellationToken,
                true
        );
        return parseCreateResult(createResult.stdout());
    }

    private String primaryCalendarId(CancellationToken cancellationToken) {
        LarkCliProcessResult result = execute(
                List.of(
                        cliPath.toString(),
                        "calendar", "calendars", "primary",
                        "--as", "user",
                        "--format", "json"
                ),
                "",
                "querying the primary Feishu calendar",
                cancellationToken,
                false
        );
        cancellationToken.throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
        JsonNode response = parseResponse(result.stdout(), "primary calendar");
        JsonNode calendars = response.path("calendars");
        if (!calendars.isArray()) {
            throw invalidResponse("primary calendar");
        }
        for (JsonNode calendarEntry : calendars) {
            Optional<String> calendarId = nonBlankText(calendarEntry.path("calendar").path("calendar_id"));
            if (calendarId.isPresent()) {
                return calendarId.orElseThrow();
            }
        }
        throw invalidResponse("primary calendar");
    }

    private LarkCliProcessResult execute(List<String> argv,
                                         String stdin,
                                         String operation,
                                         CancellationToken cancellationToken,
                                         boolean retryOnceOnTimeout) {
        int maxAttempts = retryOnceOnTimeout ? 2 : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                cancellationToken.throwIfCancellationRequested(CancellationPhase.TOOL_EXECUTION);
                LarkCliProcessResult result = processExecutor.execute(argv, stdin, timeout, cancellationToken);
                if (result.exitCode() != 0) {
                    throw new FeishuCalendarGatewayException(
                            "lark-cli failed while " + operation + " (exit code " + result.exitCode() + ")"
                    );
                }
                return result;
            } catch (CancellationRequestedException exception) {
                throw exception;
            } catch (TimeoutException exception) {
                if (attempt < maxAttempts) {
                    continue;
                }
                throw new FeishuCalendarGatewayException(
                        "lark-cli timed out while " + operation
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new FeishuCalendarGatewayException(
                        "lark-cli was interrupted while " + operation
                );
            } catch (IOException exception) {
                throw new FeishuCalendarGatewayException(
                        "Failed to execute lark-cli while " + operation
                );
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static String createPayload(FeishuCalendarCreateRequest request) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("summary", request.summary());
        request.description().ifPresent(description -> payload.put("description", description));
        payload.set("start_time", eventTime(request.start()));
        payload.set("end_time", eventTime(request.end()));
        payload.putArray("reminders").addObject().put("minutes", request.reminderMinutes());
        payload.put("visibility", "private");
        payload.put("free_busy_status", "busy");
        payload.putObject("vchat").put("vc_type", "no_meeting");
        return payload.toString();
    }

    private static ObjectNode eventTime(ZonedDateTime value) {
        ObjectNode time = JSON.createObjectNode();
        time.put("timestamp", Long.toString(value.toEpochSecond()));
        time.put("timezone", value.getZone().getId());
        return time;
    }

    private static FeishuCalendarCreateResult parseCreateResult(String stdout) {
        JsonNode event = parseResponse(stdout, "event creation").path("event");
        String eventId = nonBlankText(event.path("event_id"))
                .orElseThrow(() -> invalidResponse("event creation"));
        Optional<String> appLink = nonBlankText(event.path("app_link"));
        return new FeishuCalendarCreateResult(eventId, appLink);
    }

    private static JsonNode parseResponse(String stdout, String responseName) {
        try {
            JsonNode root = JSON.readTree(stdout);
            if (root == null || !root.isObject()) {
                throw invalidResponse(responseName);
            }
            JsonNode data = root.path("data");
            return data.isObject() ? data : root;
        } catch (JsonProcessingException exception) {
            throw new FeishuCalendarGatewayException(
                    "lark-cli returned an invalid " + responseName + " response"
            );
        }
    }

    private static Optional<String> nonBlankText(JsonNode node) {
        if (!node.isTextual() || node.textValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(node.textValue());
    }

    private static FeishuCalendarGatewayException invalidResponse(String responseName) {
        return new FeishuCalendarGatewayException(
                "lark-cli returned an invalid " + responseName + " response"
        );
    }

    private static Duration requirePositive(Duration timeout) {
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
