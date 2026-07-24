package minicode.integrations.feishu.calendar;

import java.util.Objects;

/**
 * 保留用户原始小时表达的时钟时间。
 */
public record CalendarClockTime(int hour, int minute, CalendarDayPeriod dayPeriod) {
    public CalendarClockTime {
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("minute must be between 0 and 59");
        }
        dayPeriod = Objects.requireNonNull(dayPeriod, "dayPeriod");
        validateHour(hour, dayPeriod);
    }

    private static void validateHour(int hour, CalendarDayPeriod dayPeriod) {
        int minimum;
        int maximum;
        switch (dayPeriod) {
            case UNSPECIFIED -> {
                minimum = 0;
                maximum = 23;
            }
            case MORNING -> {
                minimum = 0;
                maximum = 11;
            }
            case AFTERNOON -> {
                minimum = 1;
                maximum = 12;
            }
            case EVENING -> {
                minimum = 1;
                maximum = 11;
            }
            case EARLY_MORNING -> {
                minimum = 0;
                maximum = 6;
            }
            default -> throw new IllegalStateException("unsupported day period: " + dayPeriod);
        }
        if (hour < minimum || hour > maximum) {
            throw new IllegalArgumentException(
                    "hour must be between " + minimum + " and " + maximum + " for " + dayPeriod
            );
        }
    }
}
