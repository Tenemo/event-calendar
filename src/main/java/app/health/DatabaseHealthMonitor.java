package app.health;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javax.sql.DataSource;

@ApplicationScoped
public class DatabaseHealthMonitor {
    static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;
    static final Duration RESULT_CACHE_DURATION = Duration.ofSeconds(1);
    static final Duration FAILURE_WARNING_INTERVAL = Duration.ofMinutes(5);

    private static final Logger LOGGER = Logger.getLogger(DatabaseHealthMonitor.class.getName());

    @Resource(lookup = "jdbc/CalendarDataSource")
    private DataSource dataSource;

    private Clock clock;
    private Duration resultCacheDuration;
    private Consumer<String> warningSink;
    private Boolean cachedResult;
    private Instant cacheExpiration;
    private Instant nextFailureWarningAt;
    private int suppressedFailureCount;

    public DatabaseHealthMonitor() {
        clock = Clock.systemUTC();
        resultCacheDuration = RESULT_CACHE_DURATION;
        warningSink = LOGGER::warning;
    }

    DatabaseHealthMonitor(DataSource dataSource, Clock clock, Duration resultCacheDuration) {
        this(dataSource, clock, resultCacheDuration, LOGGER::warning);
    }

    DatabaseHealthMonitor(
            DataSource dataSource,
            Clock clock,
            Duration resultCacheDuration,
            Consumer<String> warningSink) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.clock = Objects.requireNonNull(clock);
        if (resultCacheDuration == null || resultCacheDuration.isZero() || resultCacheDuration.isNegative()) {
            throw new IllegalArgumentException("Result cache duration must be positive.");
        }
        this.resultCacheDuration = resultCacheDuration;
        this.warningSink = Objects.requireNonNull(warningSink);
    }

    public synchronized boolean isDatabaseUsable() {
        Instant now = clock.instant();
        if (cachedResult != null && now.isBefore(cacheExpiration)) {
            return cachedResult;
        }

        boolean currentResult = validateDatabaseConnection();
        cachedResult = currentResult;
        cacheExpiration = clock.instant().plus(resultCacheDuration);
        return currentResult;
    }

    private boolean validateDatabaseConnection() {
        try (Connection connection = dataSource.getConnection()) {
            boolean connectionValid = connection.isValid(
                    DATABASE_VALIDATION_TIMEOUT_SECONDS);
            if (!connectionValid) {
                reportValidationFailure("invalid_connection", null);
            }
            return connectionValid;
        } catch (SQLException | RuntimeException exception) {
            reportValidationFailure(exception);
            return false;
        }
    }

    private void reportValidationFailure(Exception failure) {
        reportValidationFailure(failure.getClass().getName(), failure);
    }

    private void reportValidationFailure(
            String failureType,
            Exception failure) {
        Instant now = clock.instant();
        if (nextFailureWarningAt != null && now.isBefore(nextFailureWarningAt)) {
            if (suppressedFailureCount < Integer.MAX_VALUE) {
                suppressedFailureCount++;
            }
            return;
        }

        String warningMessage = "database_health_validation_failed failure_type="
                + failureType
                + " suppressed_failures="
                + suppressedFailureCount;
        suppressedFailureCount = 0;
        nextFailureWarningAt = now.plus(FAILURE_WARNING_INTERVAL);
        try {
            warningSink.accept(warningMessage);
        } catch (RuntimeException diagnosticFailure) {
            if (failure != null && failure != diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
        }
    }
}
