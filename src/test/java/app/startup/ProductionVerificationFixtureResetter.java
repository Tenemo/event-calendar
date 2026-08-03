package app.startup;

import app.calendar.CalendarLinkToken;
import app.fixture.CanonicalCalendarFixture;
import app.fixture.CanonicalCalendarFixture.Installation;
import app.fixture.CanonicalCalendarFixture.Scope;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class ProductionVerificationFixtureResetter {
    static final String CONFIRMATION_PROPERTY = "production.verification.reset.confirmation";
    static final String REQUIRED_CONFIRMATION = "calendar.social-production-verification-only";

    private static final String RESET_APPLICATION_NAME =
            "calendar-social-production-verification-reset";
    private static final Pattern DATABASE_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Set<String> APPLICATION_SCHEMA_TABLES = Set.of(
            "app_user",
            "calendar",
            "calendar_event",
            "calendar_membership",
            "flyway_schema_history",
            "invitation",
            "registration_bootstrap");
    private ProductionVerificationFixtureResetter() {
    }

    public static void main(String[] arguments) throws Exception {
        requireResetConfirmation(System.getProperty(CONFIRMATION_PROPERTY));
        Map<String, String> environment = System.getenv();
        VerificationEnvironmentConfiguration configuration =
                VerificationEnvironmentConfiguration.fromEnvironment(environment)
                        .filter(candidate -> candidate.environmentKind()
                                == VerificationEnvironmentConfiguration.EnvironmentKind.PRODUCTION)
                        .orElseThrow(() -> new IllegalStateException(
                                "Production verification provisioning must be enabled for the exact Railway target."));
        DatabaseConnectionSettings connectionSettings =
                connectionSettingsFromEnvironment(environment);

        try (Connection connection = DriverManager.getConnection(
                connectionSettings.jdbcUrl(),
                connectionSettings.username(),
                connectionSettings.password())) {
            connection.setAutoCommit(false);
            try {
                acquireResetLock(connection);
                verifyDatabaseIdentityAndSchema(connection, connectionSettings);
                FixtureAccounts fixtureAccounts = requireFixtureAccounts(connection, configuration);
                List<FixtureCalendar> fixtureCalendars =
                        requireIsolatedFixtureCalendars(connection, fixtureAccounts);
                deleteFixtureInvitations(connection, fixtureAccounts);
                deleteFixtureCalendars(connection, fixtureCalendars);
                Installation installation = resetInstallation(configuration, fixtureAccounts);
                CanonicalCalendarFixture.install(connection, installation);
                CanonicalCalendarFixture.readSummary(connection, installation)
                        .verifyExpectedFixtureShape();
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }

        System.out.printf(
                Locale.ROOT,
                "Production verification fixture reset in Railway environment %s: 3 users, 3 calendars, 7 memberships, and 25 events.%n",
                configuration.environmentId());
    }

    static void requireResetConfirmation(String confirmation) {
        if (!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "Production verification reset is disabled. Run the committed reset task with a verified Railway database tunnel.");
        }
    }

    static DatabaseConnectionSettings connectionSettingsFromEnvironment(
            Map<String, String> environment) {
        String host = requireLoopbackHost(requiredValue(environment, "PGHOST"));
        int port = parsePort(requiredValue(environment, "PGPORT"));
        String databaseName = requireDatabaseIdentifier(
                "PGDATABASE", requiredValue(environment, "PGDATABASE"));
        String username = requireDatabaseIdentifier(
                "PGUSER", requiredValue(environment, "PGUSER"));
        String password = requiredUnmodifiedValue(environment, "PGPASSWORD");
        return new DatabaseConnectionSettings(host, port, databaseName, username, password);
    }

    private static String requiredValue(Map<String, String> environment, String variableName) {
        String value = environment.get(variableName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(variableName + " is required for the production verification reset.");
        }
        return value.strip();
    }

    private static String requiredUnmodifiedValue(
            Map<String, String> environment,
            String variableName) {
        String value = environment.get(variableName);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(variableName + " is required for the production verification reset.");
        }
        return value;
    }

    private static String requireLoopbackHost(String configuredHost) {
        String normalizedHost = configuredHost.toLowerCase(Locale.ROOT);
        return switch (normalizedHost) {
            case "localhost", "127.0.0.1" -> normalizedHost;
            case "::1", "[::1]" -> "::1";
            default -> throw new IllegalArgumentException(
                    "PGHOST must be a loopback Railway database tunnel for the production verification reset.");
        };
    }

    private static int parsePort(String configuredPort) {
        try {
            int port = Integer.parseInt(configuredPort);
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException(
                        "PGPORT must be between 1 and 65535 for the production verification reset.");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "PGPORT must be a decimal port number for the production verification reset.", exception);
        }
    }

    private static String requireDatabaseIdentifier(
            String variableName,
            String configuredValue) {
        if (!DATABASE_IDENTIFIER.matcher(configuredValue).matches()) {
            throw new IllegalArgumentException(
                    variableName + " must be an unquoted PostgreSQL identifier for the production verification reset.");
        }
        return configuredValue;
    }

    private static void acquireResetLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "select pg_advisory_xact_lock(hashtext('calendar.social verification fixture'))");
        }
    }

    private static void verifyDatabaseIdentityAndSchema(
            Connection connection,
            DatabaseConnectionSettings connectionSettings) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select current_database(), current_user, current_setting('application_name')")) {
            if (!resultSet.next()
                    || !connectionSettings.databaseName().equals(resultSet.getString(1))
                    || !connectionSettings.username().equals(resultSet.getString(2))
                    || !RESET_APPLICATION_NAME.equals(resultSet.getString(3))) {
                throw new IllegalStateException(
                        "The connected database identity does not match the verified production tunnel target.");
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
        if (!publicTableNames.equals(APPLICATION_SCHEMA_TABLES)) {
            throw new IllegalStateException(
                    "Production verification reset refuses an unrecognized schema. Public tables: "
                            + String.join(", ", publicTableNames));
        }
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
                        "Production verification reset requires the calendar.social V1 migration marker.");
            }
        }
    }

    private static FixtureAccounts requireFixtureAccounts(
            Connection connection,
            VerificationEnvironmentConfiguration configuration) throws SQLException {
        Map<String, AccountState> accounts = new HashMap<>();
        String query = "select id, username, display_name, password_hash from app_user where username in (?, ?, ?) for update";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, configuration.username());
            statement.setString(2, configuration.mayaUsername());
            statement.setString(3, configuration.tomaszUsername());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    accounts.put(
                            resultSet.getString(2),
                            new AccountState(
                                    resultSet.getLong(1),
                                    resultSet.getString(2),
                                    resultSet.getString(3),
                                    resultSet.getString(4)));
                }
            }
        }
        AccountState owner = requireAccount(
                accounts, configuration.username(), configuration.displayName());
        AccountState maya = requireAccount(
                accounts, configuration.mayaUsername(), "Maya Nowak");
        AccountState tomasz = requireAccount(
                accounts, configuration.tomaszUsername(), "Tomasz Zieliński");
        return new FixtureAccounts(owner, maya, tomasz);
    }

    private static AccountState requireAccount(
            Map<String, AccountState> accounts,
            String username,
            String expectedDisplayName) {
        AccountState account = Optional.ofNullable(accounts.get(username))
                .orElseThrow(() -> new IllegalStateException(
                        "Production verification reset requires all three canonical fixture accounts."));
        if (!expectedDisplayName.equals(account.displayName())) {
            throw new IllegalStateException(
                    "Production verification reset found a fixture username with an unexpected display name.");
        }
        return account;
    }

    private static List<FixtureCalendar> requireIsolatedFixtureCalendars(
            Connection connection,
            FixtureAccounts fixtureAccounts) throws SQLException {
        Map<Long, FixtureCalendar> calendarsById = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select calendar.id, calendar.name
                from calendar_membership membership
                join calendar on calendar.id = membership.calendar_id
                where membership.user_id in (?, ?, ?)
                order by calendar.id
                for update of calendar
                """)) {
            statement.setLong(1, fixtureAccounts.owner().id());
            statement.setLong(2, fixtureAccounts.maya().id());
            statement.setLong(3, fixtureAccounts.tomasz().id());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    calendarsById.putIfAbsent(
                            resultSet.getLong(1),
                            new FixtureCalendar(resultSet.getLong(1), resultSet.getString(2)));
                }
            }
        }
        Set<Long> fixtureAccountIds = Set.of(
                fixtureAccounts.owner().id(),
                fixtureAccounts.maya().id(),
                fixtureAccounts.tomasz().id());
        for (FixtureCalendar calendar : calendarsById.values()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select user_id from calendar_membership where calendar_id = ?")) {
                statement.setLong(1, calendar.id());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        if (!fixtureAccountIds.contains(resultSet.getLong(1))) {
                            throw new IllegalStateException(
                                    "A production verification calendar includes a non-fixture account; reset refused.");
                        }
                    }
                }
            }
        }
        return List.copyOf(calendarsById.values());
    }

    private static void deleteFixtureInvitations(
            Connection connection,
            FixtureAccounts fixtureAccounts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                delete from invitation
                where created_by_user_id in (?, ?, ?)
                """)) {
            statement.setLong(1, fixtureAccounts.owner().id());
            statement.setLong(2, fixtureAccounts.maya().id());
            statement.setLong(3, fixtureAccounts.tomasz().id());
            statement.executeUpdate();
        }
    }

    private static void deleteFixtureCalendars(
            Connection connection,
            List<FixtureCalendar> fixtureCalendars) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("delete from calendar where id = ?")) {
            for (FixtureCalendar fixtureCalendar : fixtureCalendars) {
                statement.setLong(1, fixtureCalendar.id());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "Production verification reset could not delete an isolated fixture calendar.");
                }
            }
        }
    }

    private static Installation resetInstallation(
            VerificationEnvironmentConfiguration configuration,
            FixtureAccounts fixtureAccounts) {
        SecureRandom secureRandom = new SecureRandom();
        Set<String> calendarLinkTokens = new HashSet<>();
        while (calendarLinkTokens.size() < 3) {
            calendarLinkTokens.add(CalendarLinkToken.generate(secureRandom));
        }
        var calendarLinkTokenIterator = calendarLinkTokens.iterator();
        return new Installation(
                Scope.PRODUCTION,
                configuration.username(),
                configuration.displayName(),
                fixtureAccounts.owner().passwordHash(),
                true,
                configuration.mayaUsername(),
                fixtureAccounts.maya().passwordHash(),
                true,
                configuration.tomaszUsername(),
                fixtureAccounts.tomasz().passwordHash(),
                true,
                calendarLinkTokenIterator.next(),
                calendarLinkTokenIterator.next(),
                calendarLinkTokenIterator.next(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    record DatabaseConnectionSettings(
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
                    + RESET_APPLICATION_NAME
                    + "&sslmode=disable";
        }

        @Override
        public String toString() {
            return username + "@" + host + ":" + port + "/" + databaseName + " (password redacted)";
        }
    }

    private record AccountState(
            long id,
            String username,
            String displayName,
            String passwordHash) {
    }

    private record FixtureAccounts(
            AccountState owner,
            AccountState maya,
            AccountState tomasz) {
    }

    private record FixtureCalendar(long id, String name) {
    }

}
