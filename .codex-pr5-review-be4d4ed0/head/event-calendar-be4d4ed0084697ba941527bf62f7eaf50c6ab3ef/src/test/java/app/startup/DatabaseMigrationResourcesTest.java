package app.startup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DatabaseMigrationResourcesTest {
    private static final Path DATABASE_RESOURCE_DIRECTORY = Path.of("src", "main", "resources", "db");

    @Test
    void libertyClasspathLocationMarkerIsPackagedWithFlywayMigrations() {
        assertTrue(
                Files.isRegularFile(DATABASE_RESOURCE_DIRECTORY.resolve("migration/flyway.location")),
                "Open Liberty requires a marker to discover Flyway migrations on the classpath.");
    }
}
