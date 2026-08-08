package app.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ApiPreconditionsTest {
    @Test
    void parsesOnlyOneStrongCalendarEntityTag() {
        assertEquals(17, ApiPreconditions.requireCalendarVersion("\"17\""));
        assertEquals(0, ApiPreconditions.requireCalendarVersion("  \"0\"  "));

        for (String invalidEntityTag : new String[] {
            "*", "W/\"17\"", "\"17\", \"18\"", "17", "\"-1\"", "\"2147483648\""
        }) {
            ApiRequestException exception = assertThrows(
                    ApiRequestException.class,
                    () -> ApiPreconditions.requireCalendarVersion(invalidEntityTag));
            assertEquals(400, exception.status());
        }
    }

    @Test
    void missingEntityTagRequiresAPrecondition() {
        assertAll(
                () -> assertEquals(
                        428,
                        assertThrows(
                                        ApiRequestException.class,
                                        () -> ApiPreconditions.requireCalendarVersion(null))
                                .status()),
                () -> assertEquals(
                        428,
                        assertThrows(
                                        ApiRequestException.class,
                                        () -> ApiPreconditions.requireEventVersions(" "))
                                .status()));
    }

    @Test
    void eventEntityTagBindsEventAndCalendarVersions() {
        ApiPreconditions.EventVersions versions =
                ApiPreconditions.requireEventVersions("\"event-12-calendar-7\"");

        assertAll(
                () -> assertEquals(12, versions.eventVersion()),
                () -> assertEquals(7, versions.calendarVersion()),
                () -> assertThrows(
                        ApiRequestException.class,
                        () -> ApiPreconditions.requireEventVersions("\"event-12\"")),
                () -> assertThrows(
                        ApiRequestException.class,
                        () -> ApiPreconditions.requireEventVersions("W/\"event-12-calendar-7\"")));
    }
}
