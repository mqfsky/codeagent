package minicode.integrations.feishu.calendar;

import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Data needed to create one non-all-day Feishu calendar event.
 */
public record FeishuCalendarCreateRequest(String summary,
                                          ZonedDateTime start,
                                          ZonedDateTime end,
                                          int reminderMinutes,
                                          Optional<String> description) {
    private static final int MAX_REMINDER_MINUTES = 20_160;

    public FeishuCalendarCreateRequest {
        summary = requireText(summary, "summary");
        start = Objects.requireNonNull(start, "start");
        end = Objects.requireNonNull(end, "end");
        description = Objects.requireNonNull(description, "description");
        if (!end.toInstant().isAfter(start.toInstant())) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (reminderMinutes < 0 || reminderMinutes > MAX_REMINDER_MINUTES) {
            throw new IllegalArgumentException(
                    "reminderMinutes must be between 0 and " + MAX_REMINDER_MINUTES
            );
        }
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
