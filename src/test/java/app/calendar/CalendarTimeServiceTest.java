package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.util.ValidationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class CalendarTimeServiceTest {
    private final CalendarTimeService calendarTimeService = new CalendarTimeService();

    @Test
    void convertsCalendarLocalTimesUsingTheOffsetInEffectOnThatDate() {
        OffsetDateTime winterTime = calendarTimeService.toStoredTime(
                LocalDateTime.parse("2026-01-10T10:00:00"),
                "Europe/Warsaw");
        OffsetDateTime summerTime = calendarTimeService.toStoredTime(
                LocalDateTime.parse("2026-07-10T10:00:00"),
                "Europe/Warsaw");

        assertAll(
                () -> assertEquals(ZoneOffset.ofHours(1), winterTime.getOffset()),
                () -> assertEquals(ZoneOffset.ofHours(2), summerTime.getOffset()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-10T09:00:00"),
                        calendarTimeService.toCalendarTime(summerTime, "Europe/London")));
    }

    @Test
    void rejectsInvalidMissingAndAmbiguousLocalTimes() {
        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarTimeService.toStoredTime(
                                LocalDateTime.parse("2026-03-29T02:30:00"),
                                "Europe/Warsaw")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarTimeService.toStoredTime(
                                LocalDateTime.parse("2026-10-25T02:30:00"),
                                "Europe/Warsaw")),
                () -> assertThrows(ValidationException.class, () -> calendarTimeService.normalizeTimeZone("Mars/Olympus")),
                () -> assertThrows(ValidationException.class, () -> calendarTimeService.normalizeTimeZone("   ")));
    }

    @Test
    void resolvesAStartOfDayThatFallsInAMidnightClockGap() {
        OffsetDateTime resolvedStartOfDay = calendarTimeService.toStoredStartOfDay(
                LocalDate.parse("2018-11-04"),
                "America/Sao_Paulo");

        assertAll(
                () -> assertEquals(
                        OffsetDateTime.parse("2018-11-04T01:00:00-02:00"),
                        resolvedStartOfDay),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-10T00:00:00+02:00"),
                        calendarTimeService.toStoredStartOfDay(
                                LocalDate.parse("2026-07-10"),
                                "Europe/Warsaw")));
    }

    @Test
    void rejectsACivilDateThatTheTimeZoneSkippedCompletely() {
        assertThrows(
                ValidationException.class,
                () -> calendarTimeService.toStoredStartOfDay(
                        LocalDate.parse("2011-12-30"),
                        "Pacific/Apia"));
    }

    @Test
    void resolvesAnInternalExclusiveBoundaryPastASkippedCivilDate() {
        assertEquals(
                OffsetDateTime.parse("2011-12-31T00:00:00+14:00"),
                calendarTimeService.toStoredExclusiveDayBoundary(
                        LocalDate.parse("2011-12-30"),
                        "Pacific/Apia"));
    }
}
