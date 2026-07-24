package minicode.integrations.feishu.calendar;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户对日历日期的结构化表达。
 */
public sealed interface CalendarDateSpec permits CalendarDateSpec.RelativeDay, CalendarDateSpec.MonthDay {
    /**
     * 相对今天的日期。目前只接受今天、明天和后天。
     */
    record RelativeDay(int offsetDays) implements CalendarDateSpec {
        public RelativeDay {
            if (offsetDays < 0 || offsetDays > 2) {
                throw new IllegalArgumentException("offsetDays must be one of 0, 1, or 2");
            }
        }
    }

    /**
     * 月日表达。年份为空时，由解析器选择当前或下一个有效年份。
     */
    record MonthDay(Optional<Integer> year, int month, int day) implements CalendarDateSpec {
        public MonthDay {
            year = Objects.requireNonNull(year, "year");
            try {
                java.time.MonthDay.of(month, day);
                if (year.isPresent()) {
                    LocalDate.of(year.orElseThrow(), month, day);
                }
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("invalid calendar month and day", exception);
            }
        }
    }
}
