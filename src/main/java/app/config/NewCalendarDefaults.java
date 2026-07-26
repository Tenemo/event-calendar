package app.config;

import app.calendar.CalendarTimeService;
import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

@Singleton
@Startup
public class NewCalendarDefaults {
    private static final String FALLBACK_DEFAULT_TIME_ZONE = "Europe/Warsaw";

    @Inject
    private CalendarTimeService calendarTimeService;

    private String configuredDefaultTimeZone =
            System.getenv().getOrDefault(
                    ApplicationEnvironmentVariables.DEFAULT_TIME_ZONE,
                    FALLBACK_DEFAULT_TIME_ZONE);
    private String defaultTimeZone;

    @PostConstruct
    public void initialize() {
        try {
            defaultTimeZone = calendarTimeService.normalizeTimeZone(configuredDefaultTimeZone);
        } catch (ValidationException exception) {
            throw new IllegalStateException(
                    "APP_TIMEZONE must be a valid IANA time zone such as Europe/Warsaw.", exception);
        }
    }

    public String getDefaultTimeZone() {
        return defaultTimeZone;
    }
}
