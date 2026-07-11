package app.config;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.calendar.CalendarTimeService;
import app.util.ValidationException;
import org.junit.jupiter.api.Test;

final class CalendarConfigurationTest {
    @Test
    void normalizesTheConfiguredDefaultTimeZoneAtStartup() {
        CalendarConfiguration calendarConfiguration = configurationWithTimeZone(" Europe/London ");

        calendarConfiguration.initialize();

        assertEquals("Europe/London", calendarConfiguration.getDefaultTimeZone());
    }

    @Test
    void rejectsAnInvalidDefaultTimeZoneAtStartupWithAnOperationalError() {
        CalendarConfiguration calendarConfiguration = configurationWithTimeZone("Unknown/TimeZone");

        IllegalStateException exception = assertThrows(IllegalStateException.class, calendarConfiguration::initialize);

        assertEquals(
                "APP_TIMEZONE must be a valid IANA time zone such as Europe/Warsaw.", exception.getMessage());
        assertInstanceOf(ValidationException.class, exception.getCause());
    }

    private static CalendarConfiguration configurationWithTimeZone(String timeZone) {
        CalendarConfiguration calendarConfiguration = new CalendarConfiguration();
        setField(calendarConfiguration, "calendarTimeService", new CalendarTimeService());
        setField(calendarConfiguration, "configuredDefaultTimeZone", timeZone);
        return calendarConfiguration;
    }
}
