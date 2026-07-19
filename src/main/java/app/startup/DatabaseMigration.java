package app.startup;

import db.migration.CalendarTimeZoneAudit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

@Singleton
@Startup
public class DatabaseMigration {
    private static final Logger LOGGER = Logger.getLogger(DatabaseMigration.class.getName());

    @Resource(lookup = "jdbc/CalendarDataSource")
    private DataSource dataSource;

    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void migrate() {
        validateStoredCalendarTimeZones();
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();

        MigrationInfo currentMigration = flyway.info().current();
        String version = currentMigration == null ? "none" : currentMigration.getVersion().getVersion();
        LOGGER.info(() -> "Database migrations are current at version " + version + ".");
    }

    private void validateStoredCalendarTimeZones() {
        try (Connection connection = dataSource.getConnection()) {
            CalendarTimeZoneAudit.validateBeforeMigration(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Could not validate stored calendar time zones before migration.",
                    exception);
        }
    }
}
