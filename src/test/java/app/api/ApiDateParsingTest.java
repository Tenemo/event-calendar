package app.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.util.ValidationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

final class ApiDateParsingTest {
    @Test
    void parsesIsoCivilDatesAndLocalTimes() {
        assertAll(
                () -> assertEquals(
                        LocalDate.parse("2026-12-31"),
                        ApiJson.parseLocalDate("2026-12-31", "firstDay")),
                () -> assertEquals(
                        LocalDateTime.parse("2026-10-25T01:30:00"),
                        ApiJson.parseLocalDateTime("2026-10-25T01:30:00", "start")));
    }

    @Test
    void rejectsOffsetsAndInvalidCivilDates() {
        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> ApiJson.parseLocalDateTime("2026-08-03T18:30:00Z", "start")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> ApiJson.parseLocalDate("2026-02-30", "firstDay")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> ApiJson.parseLocalDateTime("2026-08-03", "start")));
    }
}
