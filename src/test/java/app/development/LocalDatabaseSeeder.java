package app.development;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

    private static final String SEED_RESOURCE = "/db/local-development-seed.sql";
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
        String seedSql = readSeedSql();

        try (Connection lockConnection = openConnection(connectionSettings)) {
            acquireReseedLock(lockConnection);
            try {
                verifyDatabaseBeforeReset(lockConnection, connectionSettings);
                rebuildSchema(connectionSettings);
                SeedSummary seedSummary = seedDatabase(connectionSettings, seedSql);
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

    private static String readSeedSql() throws IOException {
        try (InputStream inputStream = LocalDatabaseSeeder.class.getResourceAsStream(SEED_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Local development seed SQL is missing from the test classpath.");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    private static SeedSummary seedDatabase(
            LocalDatabaseConnectionSettings connectionSettings,
            String seedSql) throws SQLException {
        try (Connection connection = openConnection(connectionSettings)) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(seedSql);
                }
                SeedSummary seedSummary = readSeedSummary(connection);
                connection.commit();
                return seedSummary;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static SeedSummary readSeedSummary(Connection connection) throws SQLException {
        String summaryQuery = """
                select
                    (select count(*) from app_user),
                    (select count(*) from calendar),
                    (select count(*) from calendar_membership),
                    (select count(*) from calendar_event),
                    (select count(*) from calendar_event where all_day),
                    (select count(*) from calendar_event event
                        join calendar event_calendar on event_calendar.id = event.calendar_id
                        where event_calendar.name = 'Weekend adventures'),
                    (select count(*) from calendar_event event
                        join calendar event_calendar on event_calendar.id = event.calendar_id
                        where event_calendar.name = 'Weekend adventures'
                            and event.title = 'Skiing in Italy'
                            and event.start_at = timestamptz '2027-01-15 23:00:00+00'
                            and event.end_at = timestamptz '2027-01-23 23:00:00+00'
                            and event.all_day),
                    (select count(*) from calendar where public_access_enabled),
                    (select count(distinct time_zone) from calendar),
                    (select count(*) from invitation),
                    (select count(*) from registration_bootstrap where consumed_at is not null)
                """;
        try (PreparedStatement statement = connection.prepareStatement(summaryQuery);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("The local seed summary query returned no row.");
            }
            return new SeedSummary(
                    resultSet.getInt(1),
                    resultSet.getInt(2),
                    resultSet.getInt(3),
                    resultSet.getInt(4),
                    resultSet.getInt(5),
                    resultSet.getInt(6),
                    resultSet.getInt(7),
                    resultSet.getInt(8),
                    resultSet.getInt(9),
                    resultSet.getInt(10),
                    resultSet.getInt(11));
        }
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

    private record SeedSummary(
            int userCount,
            int calendarCount,
            int membershipCount,
            int eventCount,
            int allDayEventCount,
            int weekendAdventureEventCount,
            int italySkiTripCount,
            int publicCalendarCount,
            int timeZoneCount,
            int invitationCount,
            int consumedBootstrapCount) {
        void verifyExpectedFixtureShape() {
            if (userCount != 3
                    || calendarCount != 3
                    || membershipCount != 7
                    || eventCount != 25
                    || allDayEventCount != 6
                    || weekendAdventureEventCount != 15
                    || italySkiTripCount != 1
                    || publicCalendarCount != 2
                    || timeZoneCount != 2
                    || invitationCount != 0
                    || consumedBootstrapCount != 1) {
                throw new IllegalStateException("The local seed did not produce the expected deterministic fixture shape.");
            }
        }
    }
}
