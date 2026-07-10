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
    void normalizesTheConfiguredDefaultTimezoneAtStartup() {
        CalendarConfiguration calendarConfiguration = configurationWithTimeZone(" Europe/London ");

        calendarConfiguration.initialize();

        assertEquals("Europe/London", calendarConfiguration.getDefaultTimeZone());
    }

    @Test
    void rejectsAnInvalidDefaultTimezoneAtStartupWithAnOperationalError() {
        CalendarConfiguration calendarConfiguration = configurationWithTimeZone("Unknown/Timezone");

        IllegalStateException exception = assertThrows(IllegalStateException.class, calendarConfiguration::initialize);

        assertEquals(
                "APP_TIMEZONE must be a valid IANA timezone such as Europe/Warsaw.", exception.getMessage());
        assertInstanceOf(ValidationException.class, exception.getCause());
    }

    private static CalendarConfiguration configurationWithTimeZone(String timeZone) {
        CalendarConfiguration calendarConfiguration = new CalendarConfiguration();
        setField(calendarConfiguration, "calendarTimeService", new CalendarTimeService());
        setField(calendarConfiguration, "configuredDefaultTimeZone", timeZone);
        return calendarConfiguration;
    }
}
