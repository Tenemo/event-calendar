package app.event;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.util.ValidationException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class CalendarEventServiceTest {
    private final CalendarEventService calendarEventService = new CalendarEventService();

    @Test
    void acceptsEventsWithTitleAndStrictlyIncreasingTimes() {
        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");
        OffsetDateTime endAt = startAt.plusHours(2);

        assertDoesNotThrow(() -> calendarEventService.validateEvent("Kayaking", startAt, endAt));
    }

    @Test
    void rejectsMissingTitlesAndInvalidTimeRanges() {
        OffsetDateTime startAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertAll(
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent(null, startAt, startAt.plusHours(1))),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("   ", startAt, startAt.plusHours(1))),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", null, startAt.plusHours(1))),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", startAt, null)),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", startAt, startAt)),
                () -> assertThrows(ValidationException.class, () -> calendarEventService.validateEvent("Kayaking", startAt, startAt.minusMinutes(1))));
    }
}
