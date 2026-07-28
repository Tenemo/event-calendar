package app.event;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class EventFormStateTest {
    @Test
    void defaultRangeSkipsTheNonexistentHourAtAForwardClockChange() {
        EventFormState eventForm = new EventFormState();

        eventForm.reset(
                "Europe/Warsaw",
                Clock.fixed(Instant.parse("2026-03-28T23:30:00Z"), ZoneOffset.UTC));

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2026-03-29T03:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-03-29T04:00:00"),
                        eventForm.getEndTime()),
                () -> assertEquals(LocalDate.parse("2026-03-29"), eventForm.getFirstDay()),
                () -> assertEquals(LocalDate.parse("2026-03-29"), eventForm.getLastDay()));
    }

    @Test
    void defaultRangeSkipsTheRepeatedHourAtABackwardClockChange() {
        EventFormState eventForm = new EventFormState();

        eventForm.reset(
                "Europe/Warsaw",
                Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), ZoneOffset.UTC));

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2026-10-25T03:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-10-25T04:00:00"),
                        eventForm.getEndTime()),
                () -> assertEquals(LocalDate.parse("2026-10-25"), eventForm.getFirstDay()),
                () -> assertEquals(LocalDate.parse("2026-10-25"), eventForm.getLastDay()));
    }

    @Test
    void defaultRangeKeepsARealHourAcrossAHalfHourBackwardClockChange() {
        EventFormState eventForm = new EventFormState();

        eventForm.reset(
                "Australia/Lord_Howe",
                Clock.fixed(Instant.parse("2026-04-04T13:30:00Z"), ZoneOffset.UTC));

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2026-04-05T02:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-04-05T03:00:00"),
                        eventForm.getEndTime()),
                () -> assertEquals(LocalDate.parse("2026-04-05"), eventForm.getFirstDay()),
                () -> assertEquals(LocalDate.parse("2026-04-05"), eventForm.getLastDay()));
    }

    @Test
    void timedRangeEndingAtMidnightUsesThePreviousDayAsItsInclusiveAllDayEnd() {
        EventFormState eventForm = new EventFormState();
        eventForm.setStartTime(LocalDateTime.parse("2026-07-20T23:00:00"));
        eventForm.setEndTime(LocalDateTime.parse("2026-07-21T00:00:00"));
        eventForm.setAllDaySelection(true);

        eventForm.changeAllDayMode("Europe/Warsaw");

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

        eventForm.changeAllDayMode("Europe/Warsaw");

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

        eventForm.changeAllDayMode("Europe/Warsaw");

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-22T00:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2026-07-25T00:00:00"),
                        eventForm.getEndTime()));
    }

    @Test
    void switchingFromAllDayToTimedSkipsANonexistentMidnight() {
        EventFormState eventForm = allDayEventOn("2018-11-04", "2018-11-04");

        eventForm.changeAllDayMode("America/Sao_Paulo");

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2018-11-04T01:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2018-11-05T00:00:00"),
                        eventForm.getEndTime()));
    }

    @Test
    void switchingFromAllDayToTimedMovesPastARepeatedMidnight() {
        EventFormState eventForm = allDayEventOn("2020-11-01", "2020-11-01");

        eventForm.changeAllDayMode("America/Havana");

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2020-11-01T01:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2020-11-02T00:00:00"),
                        eventForm.getEndTime()));
    }

    @Test
    void switchingFromAllDayToTimedResolvesAGapAtTheExclusiveEndBoundary() {
        EventFormState eventForm = allDayEventOn("2018-11-03", "2018-11-03");

        eventForm.changeAllDayMode("America/Sao_Paulo");

        assertAll(
                () -> assertEquals(
                        LocalDateTime.parse("2018-11-03T00:00:00"),
                        eventForm.getStartTime()),
                () -> assertEquals(
                        LocalDateTime.parse("2018-11-04T01:00:00"),
                        eventForm.getEndTime()));
    }

    private EventFormState allDayEventOn(String firstDay, String lastDay) {
        EventFormState eventForm = new EventFormState();
        eventForm.setFirstDay(LocalDate.parse(firstDay));
        eventForm.setLastDay(LocalDate.parse(lastDay));
        eventForm.setAllDay(true);
        eventForm.setAllDaySelection(false);
        return eventForm;
    }
}
