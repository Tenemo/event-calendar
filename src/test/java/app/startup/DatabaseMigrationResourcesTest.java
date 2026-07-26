package app.startup;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

final class DatabaseMigrationResourcesTest {
    private static final Path DATABASE_RESOURCE_DIRECTORY = Path.of("src", "main", "resources", "db");

    @Test
    void libertyClasspathLocationMarkerIsPackagedWithFlywayMigrations() {
        assertTrue(
                Files.isRegularFile(DATABASE_RESOURCE_DIRECTORY.resolve("migration/flyway.location")),
                "Open Liberty requires a marker to discover Flyway migrations on the classpath.");
    }

    @Test
    void canonicalCalendarTokenConstraintUsesSeparateLowLockMigrationPhases() throws IOException {
        String stagedConstraintMigration = migrationSql(
                "V13__reapply_canonical_calendar_link_token_constraint.sql");
        String validationMigration = migrationSql(
                "V14__validate_canonical_calendar_link_token_constraint.sql");
        String replacementMigration = migrationSql(
                "V15__replace_canonical_calendar_link_token_constraint.sql");

        assertAll(
                () -> assertTrue(stagedConstraintMigration.contains("set local lock_timeout = '10s'")),
                () -> assertTrue(stagedConstraintMigration.contains("not valid")),
                () -> assertFalse(stagedConstraintMigration.contains("validate constraint")),
                () -> assertFalse(stagedConstraintMigration.contains("drop constraint")),
                () -> assertTrue(validationMigration.contains("set local lock_timeout = '10s'")),
                () -> assertTrue(validationMigration.contains(
                        "validate constraint calendar_public_token_check_v13")),
                () -> assertFalse(validationMigration.contains("drop constraint")),
                () -> assertTrue(replacementMigration.contains("set local lock_timeout = '10s'")),
                () -> assertTrue(replacementMigration.contains(
                        "drop constraint if exists calendar_public_token_check")),
                () -> assertTrue(replacementMigration.contains(
                        "rename constraint calendar_public_token_check_v13 to calendar_public_token_check")),
                () -> assertFalse(replacementMigration.contains("validate constraint")));
    }

    private static String migrationSql(String fileName) throws IOException {
        return Files.readString(DATABASE_RESOURCE_DIRECTORY.resolve("migration").resolve(fileName))
                .toLowerCase(Locale.ROOT);
    }
}
