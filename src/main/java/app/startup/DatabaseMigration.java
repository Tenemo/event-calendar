package app.startup;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
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
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();

        MigrationInfo currentMigration = flyway.info().current();
        if (currentMigration == null
                || !"1".equals(currentMigration.getVersion().getVersion())) {
            throw new IllegalStateException("Database schema is not at required version 1.");
        }
        LOGGER.info("Database schema is current at version 1.");
    }
}
