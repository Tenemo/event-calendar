package app.event;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.calendar.CalendarTimeService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class CalendarEventItemTest {
    private final CalendarTimeService calendarTimeService = new CalendarTimeService();

    @Test
    void multiDayAllDayEventDisplaysBothCalendarDates() {
        CalendarEvent event = event(
                "2026-07-21T22:00:00Z",
                "2026-07-24T22:00:00Z",
                true,
                "North landing");

        CalendarEventItem item = CalendarEventItem.from(event, "Europe/Warsaw", calendarTimeService);

        assertAll(
                () -> assertEquals("Jul 22", item.getDateLabel()),
                () -> assertEquals(LocalDate.parse("2026-07-24"), item.getInclusiveEndDate()),
                () -> assertEquals(
                        "All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026",
                        item.getScheduleLabel()),
                () -> assertEquals(
                        "All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026 · North landing",
                        item.getScheduleAndLocationLabel()));
    }

    @Test
    void singleDayAllDayEventKeepsConciseLabel() {
        CalendarEvent event = event(
                "2026-07-21T22:00:00Z",
                "2026-07-22T22:00:00Z",
                true,
                null);

        CalendarEventItem item = CalendarEventItem.from(event, "Europe/Warsaw", calendarTimeService);

        assertAll(
                () -> assertEquals(LocalDate.parse("2026-07-22"), item.getInclusiveEndDate()),
                () -> assertEquals("All day", item.getScheduleLabel()),
                () -> assertEquals("All day", item.getScheduleAndLocationLabel()));
    }

    @Test
    void skippedExclusiveBoundaryStillDisplaysTheLastRealIncludedDate() {
        CalendarEvent event = event(
                "2011-12-29T00:00:00-10:00",
                "2011-12-31T00:00:00+14:00",
                true,
                null);

        CalendarEventItem item = CalendarEventItem.from(event, "Pacific/Apia", calendarTimeService);

        assertAll(
                () -> assertEquals(LocalDate.parse("2011-12-29"), item.getInclusiveEndDate()),
                () -> assertEquals("All day", item.getScheduleLabel()));
    }

    @Test
    void timedEventDisplaysCalendarTimeZoneAndLocation() {
        CalendarEvent event = event(
                "2026-07-20T08:00:00Z",
                "2026-07-20T10:00:00Z",
                false,
                "River bank");

        CalendarEventItem item = CalendarEventItem.from(event, "Europe/Warsaw", calendarTimeService);

        assertAll(
                () -> assertEquals(
                        "Mon, Jul 20, 2026 · 10:00–12:00",
                        item.getScheduleLabel()),
                () -> assertEquals(
                        "Mon, Jul 20, 2026 · 10:00–12:00 · River bank",
                        item.getScheduleAndLocationLabel()));
    }

    @Test
    void timedEventAcrossDatesKeepsBothDates() {
        CalendarEvent event = event(
                "2026-07-20T21:00:00Z",
                "2026-07-20T23:00:00Z",
                false,
                null);

        CalendarEventItem item = CalendarEventItem.from(event, "Europe/Warsaw", calendarTimeService);

        assertEquals(
                "Mon, Jul 20, 2026 at 23:00 to Tue, Jul 21, 2026 at 01:00",
                item.getScheduleLabel());
    }

    private CalendarEvent event(String startTime, String endTime, boolean allDay, String location) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle("River launch");
        event.setStartTime(OffsetDateTime.parse(startTime));
        event.setEndTime(OffsetDateTime.parse(endTime));
        event.setAllDay(allDay);
        event.setLocation(location);
        return event;
    }
}
