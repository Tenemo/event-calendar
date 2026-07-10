package app.event;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import app.calendar.CalendarTimeService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class CalendarEventRowTest {
    private final CalendarTimeService calendarTimeService = new CalendarTimeService();

    @Test
    void multiDayAllDayEventDisplaysBothCalendarDates() {
        CalendarEvent event = event(
                "2026-07-21T22:00:00Z",
                "2026-07-23T22:00:00Z",
                true,
                "North landing");

        CalendarEventRow row = CalendarEventRow.from(event, "Europe/Warsaw", calendarTimeService);

        assertAll(
                () -> assertEquals("Jul 22", row.getDateLabel()),
                () -> assertEquals(
                        "All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026",
                        row.getTimeLabel()),
                () -> assertEquals(
                        "All day from Wed, Jul 22, 2026 to Fri, Jul 24, 2026 at North landing",
                        row.getDetailLabel()));
    }

    @Test
    void singleDayAllDayEventKeepsConciseLabel() {
        CalendarEvent event = event(
                "2026-07-21T22:00:00Z",
                "2026-07-22T12:00:00Z",
                true,
                null);

        CalendarEventRow row = CalendarEventRow.from(event, "Europe/Warsaw", calendarTimeService);

        assertAll(
                () -> assertEquals("All day", row.getTimeLabel()),
                () -> assertEquals("All day", row.getDetailLabel()));
    }

    @Test
    void timedEventDisplaysCalendarTimezoneAndLocation() {
        CalendarEvent event = event(
                "2026-07-20T08:00:00Z",
                "2026-07-20T10:00:00Z",
                false,
                "River bank");

        CalendarEventRow row = CalendarEventRow.from(event, "Europe/Warsaw", calendarTimeService);

        assertAll(
                () -> assertEquals(
                        "Mon, Jul 20, 2026 at 10:00 to Mon, Jul 20, 2026 at 12:00",
                        row.getTimeLabel()),
                () -> assertEquals(
                        "Mon, Jul 20, 2026 at 10:00 to Mon, Jul 20, 2026 at 12:00 at River bank",
                        row.getDetailLabel()));
    }

    private CalendarEvent event(String startAt, String endAt, boolean allDay, String location) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle("River launch");
        event.setStartAt(OffsetDateTime.parse(startAt));
        event.setEndAt(OffsetDateTime.parse(endAt));
        event.setAllDay(allDay);
        event.setLocation(location);
        return event;
    }
}
