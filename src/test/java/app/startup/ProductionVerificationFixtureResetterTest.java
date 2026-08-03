package app.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionVerificationFixtureResetterTest {
    @Test
    void resetRequiresTheExactCommittedConfirmation() {
        assertDoesNotThrow(() -> ProductionVerificationFixtureResetter.requireResetConfirmation(
                ProductionVerificationFixtureResetter.REQUIRED_CONFIRMATION));
        assertThrows(
                IllegalStateException.class,
                () -> ProductionVerificationFixtureResetter.requireResetConfirmation(null));
        assertThrows(
                IllegalStateException.class,
                () -> ProductionVerificationFixtureResetter.requireResetConfirmation(
                        "calendar.social-production"));
    }

    @Test
    void resetDatabaseConnectionMustUseAnExplicitLoopbackTunnel() {
        for (String loopbackHost : Set.of("localhost", "127.0.0.1", "::1", "[::1]")) {
            ProductionVerificationFixtureResetter.DatabaseConnectionSettings settings =
                    ProductionVerificationFixtureResetter.connectionSettingsFromEnvironment(Map.of(
                            "PGHOST", loopbackHost,
                            "PGPORT", "55434",
                            "PGDATABASE", "railway",
                            "PGUSER", "postgres",
                            "PGPASSWORD", "database-password"));
            assertEquals(55434, settings.port());
            assertTrue(settings.jdbcUrl().contains(
                    "ApplicationName=calendar-social-production-verification-reset"));
            assertTrue(settings.jdbcUrl().endsWith("&sslmode=disable"));
        }

        for (String unsafeHost : Set.of(
                "postgres.railway.internal",
                "database.example.com",
                "localhost.example.com",
                "0.0.0.0")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProductionVerificationFixtureResetter.connectionSettingsFromEnvironment(Map.of(
                            "PGHOST", unsafeHost,
                            "PGPORT", "55434",
                            "PGDATABASE", "railway",
                            "PGUSER", "postgres",
                            "PGPASSWORD", "database-password")));
        }
    }

    @Test
    void resetConnectionDiagnosticsNeverExposeTheDatabasePassword() {
        ProductionVerificationFixtureResetter.DatabaseConnectionSettings settings =
                ProductionVerificationFixtureResetter.connectionSettingsFromEnvironment(Map.of(
                        "PGHOST", "localhost",
                        "PGPORT", "55434",
                        "PGDATABASE", "railway",
                        "PGUSER", "postgres",
                        "PGPASSWORD", "do-not-print-this"));

        assertFalse(settings.toString().contains("do-not-print-this"));
        assertTrue(settings.toString().contains("password redacted"));
    }
}
