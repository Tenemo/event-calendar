package app.development;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocalDatabaseSeederTest {
    @Test
    void confirmationMustMatchTheCommittedLocalTask() {
        assertDoesNotThrow(() -> LocalDatabaseSeeder.requireLocalReseedConfirmation(
                LocalDatabaseSeeder.REQUIRED_CONFIRMATION));
        assertThrows(
                IllegalStateException.class,
                () -> LocalDatabaseSeeder.requireLocalReseedConfirmation(null));
        assertThrows(
                IllegalStateException.class,
                () -> LocalDatabaseSeeder.requireLocalReseedConfirmation("calendar.social-local"));
    }

    @Test
    void connectionSettingsDefaultToTheRepositoryLocalDatabase() {
        LocalDatabaseSeeder.LocalDatabaseConnectionSettings connectionSettings =
                LocalDatabaseSeeder.connectionSettingsFromEnvironment(Map.of());

        assertEquals("localhost", connectionSettings.host());
        assertEquals(5432, connectionSettings.port());
        assertEquals("calendar", connectionSettings.databaseName());
        assertEquals("calendar", connectionSettings.username());
        assertTrue(connectionSettings.jdbcUrl().contains("ApplicationName=calendar-social-local-reseed"));
        assertTrue(connectionSettings.jdbcUrl().endsWith("&sslmode=disable"));
    }

    @Test
    void loopbackHostsAndCustomLocalDatabaseSettingsAreAccepted() {
        for (String loopbackHost : Set.of("localhost", "127.0.0.1", "::1", "[::1]")) {
            LocalDatabaseSeeder.LocalDatabaseConnectionSettings connectionSettings =
                    LocalDatabaseSeeder.connectionSettingsFromEnvironment(Map.of(
                            "PGHOST", loopbackHost,
                            "PGPORT", "55435",
                            "PGDATABASE", "shared_calendar_verification",
                            "PGUSER", "calendar_test",
                            "PGPASSWORD", "local-test-password"));

            assertEquals(55435, connectionSettings.port());
            assertEquals("shared_calendar_verification", connectionSettings.databaseName());
            assertEquals("calendar_test", connectionSettings.username());
        }
    }

    @Test
    void nonLoopbackAndDeceptiveHostsAreRejected() {
        for (String unsafeHost : Set.of(
                "database.example.com",
                "localhost.example.com",
                "0.0.0.0",
                "192.168.1.50",
                "postgres")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LocalDatabaseSeeder.connectionSettingsFromEnvironment(Map.of("PGHOST", unsafeHost)));
        }
    }

    @Test
    void malformedPortsAndDatabaseIdentifiersAreRejected() {
        for (String malformedPort : Set.of("0", "65536", "not-a-port", "5432?ssl=true")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> LocalDatabaseSeeder.connectionSettingsFromEnvironment(Map.of("PGPORT", malformedPort)));
        }
        for (String malformedDatabaseName : Set.of("calendar/test", "calendar?ssl=true", "calendar-name", "")) {
            Map<String, String> environment = malformedDatabaseName.isEmpty()
                    ? Map.of("PGDATABASE", " ", "PGHOST", "localhost")
                    : Map.of("PGDATABASE", malformedDatabaseName);
            if (malformedDatabaseName.isEmpty()) {
                assertEquals(
                        "calendar",
                        LocalDatabaseSeeder.connectionSettingsFromEnvironment(environment).databaseName());
            } else {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> LocalDatabaseSeeder.connectionSettingsFromEnvironment(environment));
            }
        }
    }

    @Test
    void connectionSettingsNeverExposeTheDatabasePassword() {
        LocalDatabaseSeeder.LocalDatabaseConnectionSettings connectionSettings =
                LocalDatabaseSeeder.connectionSettingsFromEnvironment(Map.of("PGPASSWORD", "do-not-print-this"));

        assertFalse(connectionSettings.toString().contains("do-not-print-this"));
        assertTrue(connectionSettings.toString().contains("password redacted"));
    }

    @Test
    void onlyEmptyOrExactApplicationSchemasCanBeReset() {
        Set<String> applicationSchemaTables = Set.of(
                "app_user",
                "calendar",
                "calendar_event",
                "calendar_membership",
                "flyway_schema_history",
                "invitation",
                "registration_bootstrap");

        assertTrue(LocalDatabaseSeeder.isRecognizedApplicationSchema(Set.of()));
        assertTrue(LocalDatabaseSeeder.isRecognizedApplicationSchema(applicationSchemaTables));
        assertFalse(LocalDatabaseSeeder.isRecognizedApplicationSchema(Set.of(
                "app_user",
                "calendar",
                "calendar_event",
                "calendar_membership",
                "flyway_schema_history",
                "registration_bootstrap")));
        assertFalse(LocalDatabaseSeeder.isRecognizedApplicationSchema(Set.of(
                "app_user",
                "calendar",
                "calendar_event",
                "calendar_membership",
                "flyway_schema_history",
                "invitation",
                "registration_bootstrap",
                "unrelated_table")));
        assertFalse(LocalDatabaseSeeder.isRecognizedApplicationSchema(Set.of("app_user", "calendar")));
        assertFalse(LocalDatabaseSeeder.isRecognizedApplicationSchema(Set.of("customer", "invoice")));
    }
}
