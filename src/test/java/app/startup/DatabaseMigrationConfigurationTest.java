package app.startup;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

final class DatabaseMigrationConfigurationTest {
    @Test
    void concurrentPostgreSqlMigrationsUseASessionLevelFlywayLock() {
        PostgreSQLConfigurationExtension postgreSqlConfiguration =
                DatabaseMigration.flywayConfiguration(new PGSimpleDataSource())
                        .getConfigurationExtension(PostgreSQLConfigurationExtension.class);

        assertFalse(postgreSqlConfiguration.isTransactionalLock());
    }
}
