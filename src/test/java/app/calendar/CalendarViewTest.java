package app.calendar;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

final class CalendarViewTest {
    @Test
    void timedRangeEndingAtMidnightUsesThePreviousDayAsItsInclusiveAllDayEnd() {
        CalendarView calendarView = new CalendarView();
        calendarView.setEventStartTime(LocalDateTime.parse("2026-07-20T23:00:00"));
        calendarView.setEventEndTime(LocalDateTime.parse("2026-07-21T00:00:00"));
        calendarView.setEventAllDay(true);

        calendarView.changeEventAllDayMode();

        assertAll(
                () -> assertEquals(LocalDate.parse("2026-07-20"), calendarView.getEventStartDate()),
                () -> assertEquals(LocalDate.parse("2026-07-20"), calendarView.getEventEndDate()));
    }

    @Test
    void timedRangeEndingAfterMidnightIncludesTheEndDateWhenSwitchingToAllDay() {
        CalendarView calendarView = new CalendarView();
        calendarView.setEventStartTime(LocalDateTime.parse("2026-07-20T23:00:00"));
        calendarView.setEventEndTime(LocalDateTime.parse("2026-07-21T00:01:00"));
        calendarView.setEventAllDay(true);

        calendarView.changeEventAllDayMode();

        assertAll(
                () -> assertEquals(LocalDate.parse("2026-07-20"), calendarView.getEventStartDate()),
                () -> assertEquals(LocalDate.parse("2026-07-21"), calendarView.getEventEndDate()));
    }

    @Test
    void switchingFromAllDayToTimedUsesTheExclusiveMidnightAfterTheLastDay() {
        CalendarView calendarView = new CalendarView();
        calendarView.setEventStartDate(LocalDate.parse("2026-07-22"));
        calendarView.setEventEndDate(LocalDate.parse("2026-07-24"));
        calendarView.setEventAllDay(false);

        calendarView.changeEventAllDayMode();

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-22T00:00:00"),
                        calendarView.getEventStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-25T00:00:00"),
                        calendarView.getEventEndTime()));
    }
}
