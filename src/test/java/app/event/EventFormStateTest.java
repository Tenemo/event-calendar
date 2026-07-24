package app.event;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

final class EventFormStateTest {
    @Test
    void timedRangeEndingAtMidnightUsesThePreviousDayAsItsInclusiveAllDayEnd() {
        EventFormState eventForm = new EventFormState();
        eventForm.setStartTime(LocalDateTime.parse("2026-07-20T23:00:00"));
        eventForm.setEndTime(LocalDateTime.parse("2026-07-21T00:00:00"));
        eventForm.setAllDaySelection(true);

        eventForm.changeAllDayMode();

        assertAll(
                () -> assertEquals(LocalDate.parse("2026-07-20"), eventForm.getFirstDay()),
                () -> assertEquals(LocalDate.parse("2026-07-20"), eventForm.getLastDay()));
    }

    @Test
    void timedRangeEndingAfterMidnightIncludesTheEndDateWhenSwitchingToAllDay() {
        EventFormState eventForm = new EventFormState();
        eventForm.setStartTime(LocalDateTime.parse("2026-07-20T23:00:00"));
        eventForm.setEndTime(LocalDateTime.parse("2026-07-21T00:01:00"));
        eventForm.setAllDaySelection(true);

        eventForm.changeAllDayMode();

        assertAll(
                () -> assertEquals(LocalDate.parse("2026-07-20"), eventForm.getFirstDay()),
                () -> assertEquals(LocalDate.parse("2026-07-21"), eventForm.getLastDay()));
    }

    @Test
    void switchingFromAllDayToTimedUsesTheExclusiveMidnightAfterTheLastDay() {
        EventFormState eventForm = new EventFormState();
        eventForm.setFirstDay(LocalDate.parse("2026-07-22"));
        eventForm.setLastDay(LocalDate.parse("2026-07-24"));
        eventForm.setAllDay(true);
        eventForm.setAllDaySelection(false);

        eventForm.changeAllDayMode();

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-22T00:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-25T00:00:00"),
                        eventForm.getEndTime()));
    }
}
