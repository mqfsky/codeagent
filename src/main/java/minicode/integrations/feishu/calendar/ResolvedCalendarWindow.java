package minicode.integrations.feishu.calendar;

import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 已解析为确定时区和瞬时时间的日历时间窗口。
 */
public record ResolvedCalendarWindow(ZonedDateTime start, ZonedDateTime end) {
    public ResolvedCalendarWindow {
        start = Objects.requireNonNull(start, "start");
        end = Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be strictly after start");
        }
    }
}
