package app.startup;

import app.config.ApplicationEnvironmentVariables;
import db.migration.CalendarTimeZoneAudit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;

@Singleton
@Startup
public class DatabaseMigration {
    static final String POSTGRESQL_TRANSACTIONAL_LOCK_CONFIGURATION =
            "flyway.postgresql.transactional.lock";

    private static final Logger LOGGER = Logger.getLogger(DatabaseMigration.class.getName());

    @Resource(lookup = "jdbc/CalendarDataSource")
    private DataSource dataSource;

    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void migrate() {
        validateStoredCalendarTimeZones();
        Flyway flyway = flywayConfiguration(dataSource).load();
        MigrationInfo currentMigration = flyway.info().current();
        DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                currentMigration == null || currentMigration.getVersion() == null
                        ? null
                        : currentMigration.getVersion().getVersion(),
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_ID),
                System.getenv(ApplicationEnvironmentVariables.RAILWAY_ENVIRONMENT_NAME),
                System.getenv(
                        ApplicationEnvironmentVariables
                                .DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED));
        flyway.migrate();

        currentMigration = flyway.info().current();
        String version = currentMigration == null ? "none" : currentMigration.getVersion().getVersion();
        LOGGER.info(() -> "Database migrations are current at version " + version + ".");
    }

    static FluentConfiguration flywayConfiguration(DataSource migrationDataSource) {
        return Flyway.configure()
                .configuration(Map.of(POSTGRESQL_TRANSACTIONAL_LOCK_CONFIGURATION, "false"))
                .dataSource(migrationDataSource)
                .locations("classpath:db/migration");
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
