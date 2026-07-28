package app.config;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.calendar.CalendarTimeService;
import app.util.ValidationException;
import org.junit.jupiter.api.Test;

final class NewCalendarDefaultsTest {
    @Test
    void normalizesTheConfiguredDefaultTimeZoneAtStartup() {
        NewCalendarDefaults newCalendarDefaults = configurationWithTimeZone(" Europe/London ");

        newCalendarDefaults.initialize();

        assertEquals("Europe/London", newCalendarDefaults.getDefaultTimeZone());
    }

    @Test
    void rejectsAnInvalidDefaultTimeZoneAtStartupWithAnOperationalError() {
        NewCalendarDefaults newCalendarDefaults = configurationWithTimeZone("Unknown/TimeZone");

        IllegalStateException exception = assertThrows(IllegalStateException.class, newCalendarDefaults::initialize);

        assertEquals(
                "APP_DEFAULT_TIME_ZONE must be a valid IANA time zone such as Europe/Warsaw.", exception.getMessage());
        assertInstanceOf(ValidationException.class, exception.getCause());
    }

    private static NewCalendarDefaults configurationWithTimeZone(String timeZone) {
        NewCalendarDefaults newCalendarDefaults = new NewCalendarDefaults();
        setField(newCalendarDefaults, "calendarTimeService", new CalendarTimeService());
        setField(newCalendarDefaults, "configuredDefaultTimeZone", timeZone);
        return newCalendarDefaults;
    }
}
