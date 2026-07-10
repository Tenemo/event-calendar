package app.config;

import app.calendar.CalendarTimeService;
import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

@Singleton
@Startup
public class CalendarConfiguration {
    private static final String DEFAULT_TIMEZONE_ENVIRONMENT_VARIABLE = "APP_TIMEZONE";
    private static final String FALLBACK_DEFAULT_TIMEZONE = "Europe/Warsaw";

    @Inject
    private CalendarTimeService calendarTimeService;

    private String configuredDefaultTimeZone =
            System.getenv().getOrDefault(DEFAULT_TIMEZONE_ENVIRONMENT_VARIABLE, FALLBACK_DEFAULT_TIMEZONE);
    private String defaultTimeZone;

    @PostConstruct
    public void initialize() {
        try {
            defaultTimeZone = calendarTimeService.normalizeTimeZone(configuredDefaultTimeZone);
        } catch (ValidationException exception) {
            throw new IllegalStateException(
                    "APP_TIMEZONE must be a valid IANA timezone such as Europe/Warsaw.", exception);
        }
    }

    public String getDefaultTimeZone() {
        return defaultTimeZone;
    }
}
