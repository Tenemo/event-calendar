package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.event.CalendarEvent;
import app.event.CalendarEventItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.primefaces.model.ScheduleEvent;
import org.primefaces.model.ScheduleModel;

final class CalendarScheduleModelFactoryTest {
    private static final String TIME_ZONE = "Europe/Warsaw";
    private final CalendarTimeService calendarTimeService = new CalendarTimeService();

    @Test
    void scheduleModelPreservesTimedAndInclusiveAllDayRanges() {
        CalendarEventItem timedEvent = event(
                "Coffee by the river",
                "Bring a thermos.",
                "River bank",
                "2026-08-22T10:00:00+02:00",
                "2026-08-22T13:00:00+02:00",
                false);
        CalendarEventItem allDayEvent = event(
                "Cabin weekend",
                null,
                null,
                "2026-08-23T00:00:00+02:00",
                "2026-08-25T00:00:00+02:00",
                true);

        ScheduleModel scheduleModel =
                CalendarScheduleModelFactory.create(List.of(timedEvent, allDayEvent));
        ScheduleEvent<?> scheduledTimedEvent = scheduleModel.getEvents().get(0);
        ScheduleEvent<?> scheduledAllDayEvent = scheduleModel.getEvents().get(1);

        assertAll(
                () -> assertTrue(scheduleModel.isEventLimit()),
                () -> assertEquals(2, scheduleModel.getEventCount()),
                () -> assertEquals("Coffee by the river", scheduledTimedEvent.getTitle()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-08-22T10:00:00"),
                        scheduledTimedEvent.getStartDate()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-08-22T13:00:00"),
                        scheduledTimedEvent.getEndDate()),
                () -> assertFalse(scheduledTimedEvent.isAllDay()),
                () -> assertEquals(Boolean.FALSE, scheduledTimedEvent.isDraggable()),
                () -> assertEquals(Boolean.FALSE, scheduledTimedEvent.isResizable()),
                () -> assertTrue(scheduledTimedEvent.getDescription().contains("River bank")),
                () -> assertTrue(scheduledTimedEvent.getDescription().contains("Bring a thermos.")),
                () -> assertEquals(
                        "calendar-event calendar-event-timed",
                        scheduledTimedEvent.getStyleClass()),
                () -> assertEquals("Cabin weekend", scheduledAllDayEvent.getTitle()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-08-23T00:00:00"),
                        scheduledAllDayEvent.getStartDate()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-08-25T00:00:00"),
                        scheduledAllDayEvent.getEndDate()),
                () -> assertTrue(scheduledAllDayEvent.isAllDay()),
                () -> assertEquals(
                        "calendar-event calendar-event-all-day",
                        scheduledAllDayEvent.getStyleClass()));
    }

    @Test
    void initialDateKeepsTheCurrentMonthWhenItContainsAnEvent() {
        LocalDate today = LocalDate.parse("2026-08-31");
        List<CalendarEventItem> events = List.of(
                eventOn("2026-08-02T18:00:00+02:00"),
                eventOn("2026-09-05T18:00:00+02:00"));

        assertEquals(today, CalendarScheduleModelFactory.findInitialDate(events, today));
    }

    @Test
    void initialDateUsesTheNearestUpcomingEventWhenTheCurrentMonthIsEmpty() {
        List<CalendarEventItem> events = List.of(
                eventOn("2026-07-20T18:00:00+02:00"),
                eventOn("2026-10-01T18:00:00+02:00"),
                eventOn("2026-09-05T18:00:00+02:00"));

        assertEquals(
                LocalDate.parse("2026-09-05"),
                CalendarScheduleModelFactory.findInitialDate(
                        events, LocalDate.parse("2026-08-31")));
    }

    @Test
    void initialDateUsesTheLatestPastEventOrTodayWhenNothingIsUpcoming() {
        LocalDate today = LocalDate.parse("2026-08-31");
        List<CalendarEventItem> pastEvents = List.of(
                eventOn("2026-06-15T18:00:00+02:00"),
                eventOn("2026-07-29T18:00:00+02:00"));

        assertAll(
                () -> assertEquals(
                        LocalDate.parse("2026-07-29"),
                        CalendarScheduleModelFactory.findInitialDate(pastEvents, today)),
                () -> assertEquals(
                        today,
                        CalendarScheduleModelFactory.findInitialDate(List.of(), today)));
    }

    private CalendarEventItem eventOn(String startTime) {
        OffsetDateTime start = OffsetDateTime.parse(startTime);
        return event(
                "Event on " + start.toLocalDate(),
                null,
                null,
                start.toString(),
                start.plusHours(1).toString(),
                false);
    }

    private CalendarEventItem event(
            String title,
            String description,
            String location,
            String startTime,
            String endTime,
            boolean allDay) {
        CalendarEvent event = new CalendarEvent();
        event.setTitle(title);
        event.setDescription(description);
        event.setLocation(location);
        event.setStartTime(OffsetDateTime.parse(startTime));
        event.setEndTime(OffsetDateTime.parse(endTime));
        event.setAllDay(allDay);
        return CalendarEventItem.from(event, TIME_ZONE, calendarTimeService);
    }
}
