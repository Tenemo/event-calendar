package app.startup;

import app.config.ApplicationEnvironmentVariables;
import org.flywaydb.core.api.MigrationVersion;

final class DescriptionMigrationMaintenanceWindow {
    private static final String PRODUCTION_ENVIRONMENT_NAME = "production";
    private static final MigrationVersion FIRST_VERSION_WITHOUT_PENDING_DESCRIPTION_MIGRATIONS =
            MigrationVersion.fromVersion("19");

    private DescriptionMigrationMaintenanceWindow() {
    }

    static void requireAcknowledged(
            String currentVersion,
            String railwayEnvironmentId,
            String railwayEnvironmentName,
            String maintenanceAcknowledgement) {
        if (!isRailwayProduction(railwayEnvironmentId, railwayEnvironmentName)
                || currentVersion == null
                || !hasPendingDescriptionMigrations(currentVersion)) {
            return;
        }
        if (isAcknowledged(maintenanceAcknowledgement)) {
            return;
        }
        throw new IllegalStateException(
                "Database migrations V16 through V19 require a production write-maintenance window. "
                        + "Scale the application service to zero, audit and repair over-limit descriptions, "
                        + "then set "
                        + ApplicationEnvironmentVariables.DESCRIPTION_MIGRATION_MAINTENANCE_ACKNOWLEDGED
                        + "=true for this migration deployment.");
    }

    private static boolean isRailwayProduction(
            String railwayEnvironmentId,
            String railwayEnvironmentName) {
        return railwayEnvironmentId != null
                && !railwayEnvironmentId.isBlank()
                && railwayEnvironmentName != null
                && PRODUCTION_ENVIRONMENT_NAME.equalsIgnoreCase(railwayEnvironmentName.trim());
    }

    private static boolean hasPendingDescriptionMigrations(String currentVersion) {
        return MigrationVersion.fromVersion(currentVersion)
                        .compareTo(FIRST_VERSION_WITHOUT_PENDING_DESCRIPTION_MIGRATIONS)
                < 0;
    }

    private static boolean isAcknowledged(String maintenanceAcknowledgement) {
        return maintenanceAcknowledgement != null
                && "true".equalsIgnoreCase(maintenanceAcknowledgement.trim());
    }
}
