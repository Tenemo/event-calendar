package app.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.config.ApplicationEnvironmentVariables;
import org.junit.jupiter.api.Test;

final class DescriptionMigrationMaintenanceWindowTest {
    private static final String RAILWAY_ENVIRONMENT_ID = "railway-production-environment";
    private static final String PRODUCTION_ENVIRONMENT_NAME = "production";

    @Test
    void productionUpgradeWithPendingDescriptionMigrationsRequiresExplicitAcknowledgement() {
        for (String currentVersion : new String[] {"1", "14", "15", "16", "17", "18"}) {
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                            currentVersion,
                            RAILWAY_ENVIRONMENT_ID,
                            PRODUCTION_ENVIRONMENT_NAME,
                            "false"));
            assertTrue(exception.getMessage().contains(
                    ApplicationEnvironmentVariables
                            .DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED));
        }
    }

    @Test
    void exactTrueAcknowledgementAllowsProductionUpgrade() {
        for (String acknowledgement : new String[] {"true", "TRUE", " true "}) {
            assertDoesNotThrow(() -> DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                    "18",
                    RAILWAY_ENVIRONMENT_ID,
                    PRODUCTION_ENVIRONMENT_NAME,
                    acknowledgement));
        }
    }

    @Test
    void freshCurrentAndNonProductionDatabasesDoNotRequireAcknowledgement() {
        assertDoesNotThrow(() -> DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                null,
                RAILWAY_ENVIRONMENT_ID,
                PRODUCTION_ENVIRONMENT_NAME,
                null));
        assertDoesNotThrow(() -> DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                "18",
                null,
                PRODUCTION_ENVIRONMENT_NAME,
                null));
        assertDoesNotThrow(() -> DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                "18",
                "railway-preview-environment",
                "preview",
                null));
    }

    @Test
    void completedDescriptionMigrationChainDoesNotRequireAcknowledgement() {
        for (String currentVersion : new String[] {"19", "20", "28"}) {
            assertDoesNotThrow(() -> DescriptionMigrationMaintenanceWindow.requireAcknowledged(
                    currentVersion,
                    RAILWAY_ENVIRONMENT_ID,
                    PRODUCTION_ENVIRONMENT_NAME,
                    null));
        }
    }
}
