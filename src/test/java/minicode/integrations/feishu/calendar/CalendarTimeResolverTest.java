package minicode.integrations.feishu.calendar;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarTimeResolverTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Clock JULY_CLOCK = Clock.fixed(Instant.parse("2026-07-24T10:30:00Z"), UTC);
    private static final CalendarTimeResolver RESOLVER = new CalendarTimeResolver(JULY_CLOCK, UTC, 60);

    @Test
    void relativeDayOnlyAllowsTodayTomorrowAndDayAfterTomorrow() {
        assertAll(
                () -> assertEquals(0, new CalendarDateSpec.RelativeDay(0).offsetDays()),
                () -> assertEquals(1, new CalendarDateSpec.RelativeDay(1).offsetDays()),
                () -> assertEquals(2, new CalendarDateSpec.RelativeDay(2).offsetDays()),
                () -> assertThrows(IllegalArgumentException.class, () -> new CalendarDateSpec.RelativeDay(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new CalendarDateSpec.RelativeDay(3))
        );
    }

    @Test
    void dateAndClockRecordsRejectInvalidValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CalendarDateSpec.MonthDay(Optional.empty(), 13, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CalendarDateSpec.MonthDay(Optional.empty(), 4, 31)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CalendarDateSpec.MonthDay(Optional.of(2025), 2, 29)),
                () -> assertEquals(29,
                        new CalendarDateSpec.MonthDay(Optional.empty(), 2, 29).day()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(24, 0, CalendarDayPeriod.UNSPECIFIED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(10, 60, CalendarDayPeriod.UNSPECIFIED)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(12, 0, CalendarDayPeriod.MORNING)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(0, 0, CalendarDayPeriod.AFTERNOON)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(15, 0, CalendarDayPeriod.AFTERNOON)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(0, 0, CalendarDayPeriod.EVENING)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(12, 0, CalendarDayPeriod.EVENING)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> time(7, 0, CalendarDayPeriod.EARLY_MORNING))
        );
    }

    @Test
    void acceptsHourBoundariesForEachDayPeriod() {
        assertAll(
                () -> assertEquals(0, time(0, 0, CalendarDayPeriod.UNSPECIFIED).hour()),
                () -> assertEquals(23, time(23, 0, CalendarDayPeriod.UNSPECIFIED).hour()),
                () -> assertEquals(0, time(0, 0, CalendarDayPeriod.MORNING).hour()),
                () -> assertEquals(11, time(11, 0, CalendarDayPeriod.MORNING).hour()),
                () -> assertEquals(1, time(1, 0, CalendarDayPeriod.AFTERNOON).hour()),
                () -> assertEquals(12, time(12, 0, CalendarDayPeriod.AFTERNOON).hour()),
                () -> assertEquals(1, time(1, 0, CalendarDayPeriod.EVENING).hour()),
                () -> assertEquals(11, time(11, 0, CalendarDayPeriod.EVENING).hour()),
                () -> assertEquals(0, time(0, 0, CalendarDayPeriod.EARLY_MORNING).hour()),
                () -> assertEquals(6, time(6, 0, CalendarDayPeriod.EARLY_MORNING).hour())
        );
    }

    @Test
    void resolvesAllSupportedRelativeDays() {
        ResolvedCalendarWindow today = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(0),
                time(11, 0, CalendarDayPeriod.UNSPECIFIED),
                Optional.empty(),
                Optional.of(30)
        );
        ResolvedCalendarWindow tomorrow = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(1),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(30)
        );
        ResolvedCalendarWindow dayAfterTomorrow = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(2),
                time(2, 0, CalendarDayPeriod.EARLY_MORNING),
                Optional.empty(),
                Optional.of(30)
        );

        assertAll(
                () -> assertEquals(LocalDate.of(2026, 7, 24), today.start().toLocalDate()),
                () -> assertEquals(LocalDate.of(2026, 7, 25), tomorrow.start().toLocalDate()),
                () -> assertEquals(LocalDate.of(2026, 7, 26), dayAfterTomorrow.start().toLocalDate())
        );
    }

    @Test
    void resolvesExplicitYearWithoutRollingItForward() {
        ResolvedCalendarWindow result = RESOLVER.resolve(
                new CalendarDateSpec.MonthDay(Optional.of(2026), 12, 20),
                time(9, 15, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(45)
        );

        assertEquals(ZonedDateTime.of(2026, 12, 20, 9, 15, 0, 0, UTC), result.start());
    }

    @Test
    void yearlessMonthDayUsesCurrentYearWhenDateHasNotPassed() {
        ResolvedCalendarWindow result = RESOLVER.resolve(
                new CalendarDateSpec.MonthDay(Optional.empty(), 12, 20),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(30)
        );

        assertEquals(LocalDate.of(2026, 12, 20), result.start().toLocalDate());
    }

    @Test
    void yearlessMonthDayUsesNextYearWhenDateHasPassed() {
        ResolvedCalendarWindow result = RESOLVER.resolve(
                new CalendarDateSpec.MonthDay(Optional.empty(), 1, 20),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(30)
        );

        assertEquals(LocalDate.of(2027, 1, 20), result.start().toLocalDate());
    }

    @Test
    void yearlessMonthDaySkipsYearsWhereDateIsInvalid() {
        Clock clock = Clock.fixed(Instant.parse("2025-03-01T00:00:00Z"), UTC);
        CalendarTimeResolver resolver = new CalendarTimeResolver(clock, UTC, 60);

        ResolvedCalendarWindow result = resolver.resolve(
                new CalendarDateSpec.MonthDay(Optional.empty(), 2, 29),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(30)
        );

        assertEquals(LocalDate.of(2028, 2, 29), result.start().toLocalDate());
    }

    @Test
    void rejectsTodayWhenStartHasPassedInsteadOfRollingToNextYear() {
        CalendarTimeResolutionException exception = assertThrows(
                CalendarTimeResolutionException.class,
                () -> RESOLVER.resolve(
                        new CalendarDateSpec.MonthDay(Optional.empty(), 7, 24),
                        time(10, 0, CalendarDayPeriod.UNSPECIFIED),
                        Optional.empty(),
                        Optional.of(30)
                )
        );

        assertTrue(exception.getMessage().contains("future"));
    }

    @Test
    void startMustBeStrictlyAfterNow() {
        assertAll(
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(0),
                        time(10, 29, CalendarDayPeriod.UNSPECIFIED),
                        Optional.empty(),
                        Optional.of(30)
                )),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(0),
                        time(10, 30, CalendarDayPeriod.UNSPECIFIED),
                        Optional.empty(),
                        Optional.of(30)
                ))
        );
    }

    @Test
    void keepsUnspecifiedMorningAndEarlyMorningHoursUnchanged() {
        ResolvedCalendarWindow unspecified = resolveTomorrow(time(23, 0, CalendarDayPeriod.UNSPECIFIED));
        ResolvedCalendarWindow morning = resolveTomorrow(time(9, 0, CalendarDayPeriod.MORNING));
        ResolvedCalendarWindow earlyMorning = resolveTomorrow(time(2, 0, CalendarDayPeriod.EARLY_MORNING));

        assertAll(
                () -> assertEquals(LocalTime.of(23, 0), unspecified.start().toLocalTime()),
                () -> assertEquals(LocalTime.of(9, 0), morning.start().toLocalTime()),
                () -> assertEquals(LocalTime.of(2, 0), earlyMorning.start().toLocalTime())
        );
    }

    @Test
    void convertsAfternoonAndEveningOneThroughElevenToTwentyFourHourTime() {
        ResolvedCalendarWindow afternoon = resolveTomorrow(time(3, 5, CalendarDayPeriod.AFTERNOON));
        ResolvedCalendarWindow evening = resolveTomorrow(time(7, 10, CalendarDayPeriod.EVENING));

        assertAll(
                () -> assertEquals(LocalTime.of(15, 5), afternoon.start().toLocalTime()),
                () -> assertEquals(LocalTime.of(19, 10), evening.start().toLocalTime())
        );
    }

    @Test
    void conservativelyKeepsAfternoonTwelveAtTwelve() {
        ResolvedCalendarWindow afternoon = resolveTomorrow(time(12, 0, CalendarDayPeriod.AFTERNOON));

        assertEquals(LocalTime.NOON, afternoon.start().toLocalTime());
    }

    @Test
    void explicitEndMustBeStrictlyLaterOnTheSameDay() {
        ResolvedCalendarWindow result = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(1),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.of(time(10, 15, CalendarDayPeriod.MORNING)),
                Optional.empty()
        );

        assertAll(
                () -> assertEquals(LocalTime.of(9, 0), result.start().toLocalTime()),
                () -> assertEquals(LocalTime.of(10, 15), result.end().toLocalTime())
        );
    }

    @Test
    void rejectsEqualEarlierAndImplicitOvernightEnd() {
        assertAll(
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(9, 0, CalendarDayPeriod.MORNING),
                        Optional.of(time(9, 0, CalendarDayPeriod.MORNING)),
                        Optional.empty()
                )),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(9, 0, CalendarDayPeriod.MORNING),
                        Optional.of(time(8, 59, CalendarDayPeriod.MORNING)),
                        Optional.empty()
                )),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(11, 0, CalendarDayPeriod.EVENING),
                        Optional.of(time(1, 0, CalendarDayPeriod.EARLY_MORNING)),
                        Optional.empty()
                ))
        );
    }

    @Test
    void explicitEndAndDurationAreMutuallyExclusive() {
        CalendarTimeResolutionException exception = assertThrows(
                CalendarTimeResolutionException.class,
                () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(9, 0, CalendarDayPeriod.MORNING),
                        Optional.of(time(10, 0, CalendarDayPeriod.MORNING)),
                        Optional.of(60)
                )
        );

        assertTrue(exception.getMessage().contains("mutually exclusive"));
    }

    @Test
    void usesExplicitDurationOrConfiguredDefault() {
        ResolvedCalendarWindow explicit = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(1),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(25)
        );
        ResolvedCalendarWindow defaulted = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(1),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.empty()
        );

        assertAll(
                () -> assertEquals(explicit.start().plusMinutes(25), explicit.end()),
                () -> assertEquals(defaulted.start().plusMinutes(60), defaulted.end())
        );
    }

    @Test
    void durationAndDefaultDurationMustBePositive() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CalendarTimeResolver(JULY_CLOCK, UTC, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new CalendarTimeResolver(JULY_CLOCK, UTC, 1441)),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(9, 0, CalendarDayPeriod.MORNING),
                        Optional.empty(),
                        Optional.of(0)
                )),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(9, 0, CalendarDayPeriod.MORNING),
                        Optional.empty(),
                        Optional.of(-1)
                )),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> RESOLVER.resolve(
                        new CalendarDateSpec.RelativeDay(1),
                        time(9, 0, CalendarDayPeriod.MORNING),
                        Optional.empty(),
                        Optional.of(1441)
                ))
        );
    }

    @Test
    void acceptsOneDayAsMaximumDuration() {
        CalendarTimeResolver resolver = new CalendarTimeResolver(JULY_CLOCK, UTC, 1440);

        ResolvedCalendarWindow explicit = RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(1),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.of(1440)
        );
        ResolvedCalendarWindow defaulted = resolver.resolve(
                new CalendarDateSpec.RelativeDay(1),
                time(9, 0, CalendarDayPeriod.MORNING),
                Optional.empty(),
                Optional.empty()
        );

        assertAll(
                () -> assertEquals(explicit.start().plusDays(1), explicit.end()),
                () -> assertEquals(defaulted.start().plusDays(1), defaulted.end())
        );
    }

    @Test
    void rejectsStartInsideDstGap() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Clock clock = Clock.fixed(Instant.parse("2026-03-07T17:00:00Z"), newYork);
        CalendarTimeResolver resolver = new CalendarTimeResolver(clock, newYork, 60);

        CalendarTimeResolutionException exception = assertThrows(
                CalendarTimeResolutionException.class,
                () -> resolver.resolve(
                        new CalendarDateSpec.MonthDay(Optional.of(2026), 3, 8),
                        time(2, 30, CalendarDayPeriod.UNSPECIFIED),
                        Optional.empty(),
                        Optional.of(30)
                )
        );

        assertTrue(exception.getMessage().contains("gap"));
    }

    @Test
    void rejectsStartInsideDstOverlap() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), newYork);
        CalendarTimeResolver resolver = new CalendarTimeResolver(clock, newYork, 60);

        CalendarTimeResolutionException exception = assertThrows(
                CalendarTimeResolutionException.class,
                () -> resolver.resolve(
                        new CalendarDateSpec.MonthDay(Optional.of(2026), 11, 1),
                        time(1, 30, CalendarDayPeriod.UNSPECIFIED),
                        Optional.empty(),
                        Optional.of(30)
                )
        );

        assertTrue(exception.getMessage().contains("ambiguous"));
    }

    @Test
    void rejectsExplicitEndInsideDstGapOrOverlap() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Clock springClock = Clock.fixed(Instant.parse("2026-03-07T17:00:00Z"), newYork);
        CalendarTimeResolver springResolver = new CalendarTimeResolver(springClock, newYork, 60);
        Clock autumnClock = Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), newYork);
        CalendarTimeResolver autumnResolver = new CalendarTimeResolver(autumnClock, newYork, 60);

        assertAll(
                () -> assertThrows(CalendarTimeResolutionException.class, () -> springResolver.resolve(
                        new CalendarDateSpec.MonthDay(Optional.of(2026), 3, 8),
                        time(1, 30, CalendarDayPeriod.UNSPECIFIED),
                        Optional.of(time(2, 30, CalendarDayPeriod.UNSPECIFIED)),
                        Optional.empty()
                )),
                () -> assertThrows(CalendarTimeResolutionException.class, () -> autumnResolver.resolve(
                        new CalendarDateSpec.MonthDay(Optional.of(2026), 11, 1),
                        time(0, 30, CalendarDayPeriod.UNSPECIFIED),
                        Optional.of(time(1, 30, CalendarDayPeriod.UNSPECIFIED)),
                        Optional.empty()
                ))
        );
    }

    private static ResolvedCalendarWindow resolveTomorrow(CalendarClockTime startTime) {
        return RESOLVER.resolve(
                new CalendarDateSpec.RelativeDay(1),
                startTime,
                Optional.empty(),
                Optional.of(30)
        );
    }

    private static CalendarClockTime time(int hour, int minute, CalendarDayPeriod dayPeriod) {
        return new CalendarClockTime(hour, minute, dayPeriod);
    }
}
