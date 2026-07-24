package minicode.integrations.feishu.calendar;

import java.util.Objects;
import java.util.Optional;

/**
 * Stable identifiers returned after a Feishu event is created.
 */
public record FeishuCalendarCreateResult(String eventId, Optional<String> appLink) {
    public FeishuCalendarCreateResult {
        eventId = requireText(eventId, "eventId");
        appLink = Objects.requireNonNull(appLink, "appLink");
        if (appLink.isPresent() && appLink.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("appLink must not be blank when present");
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
