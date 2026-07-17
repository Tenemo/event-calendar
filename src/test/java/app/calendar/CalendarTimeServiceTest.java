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
                () -> assertThrows(ValidationException.class, () -> calendarTimeService.normalizeTimeZone("+01:00")),
                () -> assertThrows(ValidationException.class, () -> calendarTimeService.normalizeTimeZone("UTC+01:00")),
                () -> assertThrows(ValidationException.class, () -> calendarTimeService.normalizeTimeZone("Z")),
                () -> assertThrows(ValidationException.class, () -> calendarTimeService.normalizeTimeZone("   ")));
    }

    @Test
    void acceptsSupportedRegionIdentifiersAndUtc() {
        assertAll(
                () -> assertEquals("Europe/Warsaw", calendarTimeService.normalizeTimeZone(" Europe/Warsaw ")),
                () -> assertEquals("America/New_York", calendarTimeService.normalizeTimeZone("America/New_York")),
                () -> assertEquals("UTC", calendarTimeService.normalizeTimeZone("UTC")));
    }

    @Test
    void convertsInclusiveAllDayDatesIntoOneExclusiveStoredRange() {
        CalendarTimeService.StoredAllDayRange storedRange = calendarTimeService.toStoredAllDayRange(
                LocalDate.parse("2026-03-28"),
                LocalDate.parse("2026-03-29"),
                "Europe/Warsaw");

        assertAll(
                () -> assertEquals(OffsetDateTime.parse("2026-03-28T00:00:00+01:00"), storedRange.startTime()),
                () -> assertEquals(OffsetDateTime.parse("2026-03-30T00:00:00+02:00"), storedRange.endTime()),
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarTimeService.toStoredAllDayRange(
                                LocalDate.parse("2011-12-29"),
                                LocalDate.parse("2011-12-30"),
                                "Pacific/Apia")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> calendarTimeService.toStoredAllDayRange(
                                LocalDate.parse("2026-03-29"),
                                LocalDate.parse("2026-03-28"),
                                "Europe/Warsaw")));
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
