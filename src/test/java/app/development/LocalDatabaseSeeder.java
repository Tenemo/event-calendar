package app.development;

import app.fixture.CanonicalCalendarFixture;
import app.fixture.CanonicalCalendarFixture.FixtureSummary;
import app.fixture.CanonicalCalendarFixture.Installation;
import app.fixture.CanonicalCalendarFixture.Scope;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

public final class LocalDatabaseSeeder {
    static final String CONFIRMATION_PROPERTY = "local.reseed.confirmation";
    static final String REQUIRED_CONFIRMATION = "calendar.social-local-only";
    static final String RESEED_APPLICATION_NAME = "calendar-social-local-reseed";

    private static final String ADMIN_PASSWORD_HASH =
            "PBKDF2WithHmacSHA256:600000:ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=:"
                    + "xI7oQ7nFFZ0B3d9uVkzb1GXB1RLQvYYpEVk8dPM9xDc=";
    private static final String MAYA_PASSWORD_HASH =
            "PBKDF2WithHmacSHA256:600000:QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=:"
                    + "rPPiqJDII1ElPaGFy6r9pfgnIe2c6hZn3JLs2yFB6TE=";
    private static final String TOMASZ_PASSWORD_HASH =
            "PBKDF2WithHmacSHA256:600000:YGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn8=:"
                    + "KHGGJ8gaxdaGF7xTQEbCiAm3PPciFdyX+RZCVMvCspg=";
    private static final String DEFAULT_DATABASE_HOST = "localhost";
    private static final int DEFAULT_DATABASE_PORT = 5432;
    private static final String DEFAULT_DATABASE_NAME = "calendar";
    private static final String DEFAULT_DATABASE_USER = "calendar";
    private static final String DEFAULT_DATABASE_PASSWORD = "calendar";
    private static final Pattern DATABASE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Set<String> APPLICATION_SCHEMA_TABLES = Set.of(
            "app_user",
            "calendar",
            "calendar_event",
            "calendar_membership",
            "flyway_schema_history",
            "invitation",
            "registration_bootstrap");

    private LocalDatabaseSeeder() {}

    public static void main(String[] arguments) throws Exception {
        requireLocalReseedConfirmation(System.getProperty(CONFIRMATION_PROPERTY));
        LocalDatabaseConnectionSettings connectionSettings = connectionSettingsFromEnvironment(System.getenv());
        Installation fixtureInstallation = localFixtureInstallation();

        try (Connection lockConnection = openConnection(connectionSettings)) {
            acquireReseedLock(lockConnection);
            try {
                verifyDatabaseBeforeReset(lockConnection, connectionSettings);
                rebuildSchema(connectionSettings);
                FixtureSummary seedSummary = seedDatabase(connectionSettings, fixtureInstallation);
                seedSummary.verifyExpectedFixtureShape();
                System.out.printf(
                        Locale.ROOT,
                        "Local database reseeded at %s:%d/%s: %d users, %d calendars, %d memberships, and %d events.%n",
                        connectionSettings.host(),
                        connectionSettings.port(),
                        connectionSettings.databaseName(),
                        seedSummary.userCount(),
                        seedSummary.calendarCount(),
                        seedSummary.membershipCount(),
                        seedSummary.eventCount());
            } finally {
                releaseReseedLock(lockConnection);
            }
        }
    }

    static void requireLocalReseedConfirmation(String confirmation) {
        if (!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "Local reseeding is disabled. Run the committed `mise run reseed-local` task.");
        }
    }

    static LocalDatabaseConnectionSettings connectionSettingsFromEnvironment(Map<String, String> environment) {
        String host = requireLoopbackHost(environmentValue(environment, "PGHOST", DEFAULT_DATABASE_HOST));
        int port = parsePort(environmentValue(environment, "PGPORT", Integer.toString(DEFAULT_DATABASE_PORT)));
        String databaseName = requireDatabaseIdentifier(
                "PGDATABASE", environmentValue(environment, "PGDATABASE", DEFAULT_DATABASE_NAME));
        String username = requireDatabaseIdentifier(
                "PGUSER", environmentValue(environment, "PGUSER", DEFAULT_DATABASE_USER));
        String password = environmentValue(environment, "PGPASSWORD", DEFAULT_DATABASE_PASSWORD);
        if (password.isBlank()) {
            throw new IllegalArgumentException("PGPASSWORD must not be blank for local reseeding.");
        }
        return new LocalDatabaseConnectionSettings(host, port, databaseName, username, password);
    }

    static boolean isRecognizedApplicationSchema(Set<String> publicTableNames) {
        return publicTableNames.isEmpty() || publicTableNames.equals(APPLICATION_SCHEMA_TABLES);
    }

    private static String environmentValue(
            Map<String, String> environment,
            String variableName,
            String defaultValue) {
        String configuredValue = environment.get(variableName);
        return configuredValue == null || configuredValue.isBlank()
                ? defaultValue
                : configuredValue.strip();
    }

    private static String requireLoopbackHost(String configuredHost) {
        String normalizedHost = configuredHost.strip().toLowerCase(Locale.ROOT);
        return switch (normalizedHost) {
            case "localhost", "127.0.0.1" -> normalizedHost;
            case "::1", "[::1]" -> "::1";
            default -> throw new IllegalArgumentException(
                    "PGHOST must be localhost, 127.0.0.1, or ::1 for local reseeding.");
        };
    }

    private static int parsePort(String configuredPort) {
        try {
            int port = Integer.parseInt(configuredPort);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("PGPORT must be between 1 and 65535 for local reseeding.");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("PGPORT must be a decimal port number for local reseeding.", exception);
        }
    }

    private static String requireDatabaseIdentifier(String variableName, String configuredValue) {
        if (!DATABASE_IDENTIFIER.matcher(configuredValue).matches()) {
            throw new IllegalArgumentException(
                    variableName + " must be an unquoted PostgreSQL identifier for local reseeding.");
        }
        return configuredValue;
    }

    private static Connection openConnection(LocalDatabaseConnectionSettings connectionSettings) throws SQLException {
        return DriverManager.getConnection(
                connectionSettings.jdbcUrl(),
                connectionSettings.username(),
                connectionSettings.password());
    }

    private static void acquireReseedLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("select pg_advisory_lock(hashtext('calendar.social local reseed'))");
        }
    }

    private static void releaseReseedLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("select pg_advisory_unlock(hashtext('calendar.social local reseed'))");
        }
    }

    private static void verifyDatabaseBeforeReset(
            Connection connection,
            LocalDatabaseConnectionSettings connectionSettings) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select current_database(), current_user, current_setting('application_name')")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("The local database identity query returned no row.");
            }
            String currentDatabase = resultSet.getString(1);
            String currentUser = resultSet.getString(2);
            String applicationName = resultSet.getString(3);
            if (!connectionSettings.databaseName().equals(currentDatabase)
                    || !connectionSettings.username().equals(currentUser)
                    || !RESEED_APPLICATION_NAME.equals(applicationName)) {
                throw new IllegalStateException("The connected database identity does not match the verified local target.");
            }
        }

        Set<String> publicTableNames = new TreeSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select tablename from pg_catalog.pg_tables where schemaname = 'public'")) {
            while (resultSet.next()) {
                publicTableNames.add(resultSet.getString(1));
            }
        }
        if (!isRecognizedApplicationSchema(publicTableNames)) {
            throw new IllegalStateException(
                    "Refusing to reset an unrecognized local schema. Public tables: "
                            + String.join(", ", publicTableNames));
        }
        if (!publicTableNames.isEmpty()) {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("""
                            select count(*)
                            from flyway_schema_history
                            where version = '1'
                                and description = 'initial schema'
                                and success
                            """)) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "Refusing to reset a local schema without the calendar.social V1 migration marker.");
                }
            }
        }
    }

    private static void rebuildSchema(LocalDatabaseConnectionSettings connectionSettings) {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        connectionSettings.jdbcUrl(),
                        connectionSettings.username(),
                        connectionSettings.password())
                .locations("classpath:db/migration")
                .schemas("public")
                .cleanDisabled(false)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .load();
        flyway.clean();
        flyway.migrate();

        MigrationInfo currentMigration = flyway.info().current();
        if (currentMigration == null
                || currentMigration.getVersion() == null
                || !"1".equals(currentMigration.getVersion().getVersion())) {
            throw new IllegalStateException("The local database schema did not migrate to required version 1.");
        }
    }

    private static FixtureSummary seedDatabase(
            LocalDatabaseConnectionSettings connectionSettings,
            Installation fixtureInstallation) throws SQLException {
        try (Connection connection = openConnection(connectionSettings)) {
            connection.setAutoCommit(false);
            try {
                CanonicalCalendarFixture.install(connection, fixtureInstallation);
                FixtureSummary seedSummary =
                        CanonicalCalendarFixture.readSummary(connection, fixtureInstallation);
                connection.commit();
                return seedSummary;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Installation localFixtureInstallation() {
        return new Installation(
                Scope.LOCAL,
                "admin",
                "Local admin",
                ADMIN_PASSWORD_HASH,
                false,
                "maya",
                MAYA_PASSWORD_HASH,
                false,
                "tomasz",
                TOMASZ_PASSWORD_HASH,
                false,
                "3e508947ceA",
                "ca62893117E",
                "f812ffe4b2I",
                OffsetDateTime.parse("2026-07-01T10:00:00Z"));
    }

    record LocalDatabaseConnectionSettings(
            String host,
            int port,
            String databaseName,
            String username,
            String password) {
        String jdbcUrl() {
            String jdbcHost = "::1".equals(host) ? "[::1]" : host;
            return "jdbc:postgresql://"
                    + jdbcHost
                    + ":"
                    + port
                    + "/"
                    + databaseName
                    + "?ApplicationName="
                    + RESEED_APPLICATION_NAME
                    + "&sslmode=disable";
        }

        @Override
        public String toString() {
            return username + "@" + host + ":" + port + "/" + databaseName + " (password redacted)";
        }
    }

}
