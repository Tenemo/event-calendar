import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class CalendarToolPostgreSql extends CalendarToolProcessRunner {
    static final String POSTGRESQL_HOST_ENVIRONMENT_VARIABLE = "PGHOST";
    static final String POSTGRESQL_PORT_ENVIRONMENT_VARIABLE = "PGPORT";
    static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    static final String POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE = "PGPASSWORD";
    static final String POSTGRESQL_APPLICATION_NAME_ENVIRONMENT_VARIABLE = "PGAPPLICATIONNAME";
    static final String DATABASE_SERVICE_NAME = "postgres";

    private static final String DEFAULT_DATABASE_NAME = "calendar";
    private static final String DEFAULT_DATABASE_USER = "calendar";
    private static final String EXPECTED_FLYWAY_VERSION = "28";
    private static final Duration DATABASE_READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DATABASE_READY_POLL_INTERVAL = Duration.ofSeconds(2);

    private CalendarToolPostgreSql() {}

    static void startDatabase() throws IOException, InterruptedException {
        runCommand("PostgreSQL startup", "docker", "compose", "up", "-d", DATABASE_SERVICE_NAME);
        waitForDatabase();
    }

    static void checkDatabaseSchema() throws IOException, InterruptedException {
        checkComposeDatabaseSchema(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
    }

    static void verifyComposeApplicationDatabaseConfiguration()
            throws IOException, InterruptedException {
        String configuredValues = runCommandAndCapture(
                "Compose application database configuration verification",
                "docker",
                "compose",
                "exec",
                "-T",
                "web",
                "printenv",
                POSTGRESQL_HOST_ENVIRONMENT_VARIABLE,
                POSTGRESQL_PORT_ENVIRONMENT_VARIABLE,
                POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE,
                POSTGRESQL_USER_ENVIRONMENT_VARIABLE,
                POSTGRESQL_APPLICATION_NAME_ENVIRONMENT_VARIABLE);
        validateComposeApplicationDatabaseConfiguration(
                configuredValues,
                databaseName(),
                databaseUser(),
                CalendarToolVerification.composeDatabaseApplicationName(System.getenv()));
    }

    static void validateComposeApplicationDatabaseConfiguration(
            String configuredValues,
            String expectedDatabaseName,
            String expectedDatabaseUser,
            String expectedApplicationName) {
        List<String> values = configuredValues == null
                ? List.of()
                : configuredValues.lines().map(String::trim).toList();
        List<String> expectedValues = List.of(
                DATABASE_SERVICE_NAME,
                "5432",
                expectedDatabaseName,
                expectedDatabaseUser,
                expectedApplicationName);
        if (!values.equals(expectedValues)) {
            throw new IllegalStateException(
                    "The running Compose application is not configured for the intended database.");
        }
    }

    static void verifyApplicationConnectionToComposeDatabase(String expectedApplicationName)
            throws IOException, InterruptedException {
        if (expectedApplicationName == null
                || !expectedApplicationName.matches("[A-Za-z0-9._-]{1,63}")) {
            throw new IllegalArgumentException("The expected PostgreSQL application name is invalid.");
        }
        String connectionCount = runCommandAndCapture(
                "Application database connection verification",
                composeSqlCommand(
                        DATABASE_SERVICE_NAME,
                        databaseUser(),
                        databaseName(),
                        "select count(*) from pg_stat_activity "
                                + "where datname = current_database() "
                                + "and usename = current_user "
                                + "and application_name = '" + expectedApplicationName + "' "
                                + "and pid <> pg_backend_pid();"))
                .trim();
        int parsedConnectionCount;
        try {
            parsedConnectionCount = Integer.parseInt(connectionCount);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "PostgreSQL returned an invalid application-connection count.", exception);
        }
        if (parsedConnectionCount < 1) {
            throw new IllegalStateException(
                    "No JDBC connection from the application was observed on the local database.");
        }
    }

    static void checkComposeDatabaseSchema(
            String databaseServiceName,
            String databaseUser,
            String databaseName) throws IOException, InterruptedException {
        String migrationState = runCommandAndCapture(
                "Flyway schema verification",
                composeSqlCommand(
                        databaseServiceName,
                        databaseUser,
                        databaseName,
                        "select (select count(*) from flyway_schema_history where success is distinct from true)::text"
                                + " || '|' || coalesce((select version from flyway_schema_history"
                                + " where success and version is not null order by installed_rank desc limit 1), '');"))
                .trim();
        String expectedMigrationState = "0|" + EXPECTED_FLYWAY_VERSION;
        if (!expectedMigrationState.equals(migrationState)) {
            throw new IllegalStateException(
                    "Flyway schema verification expected no failed migrations and version "
                            + EXPECTED_FLYWAY_VERSION + ", but got '" + migrationState + "'.");
        }
        System.out.println("Flyway schema is current at version " + EXPECTED_FLYWAY_VERSION + ".");
    }

    static void waitForDatabase() throws IOException, InterruptedException {
        waitForComposeDatabase(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
    }

    static void waitForComposeDatabase(
            String databaseServiceName,
            String databaseUser,
            String databaseName) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + DATABASE_READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            int readinessExitCode = runCommandForExitCode(
                    false,
                    "docker",
                    "compose",
                    "exec",
                    "-T",
                    databaseServiceName,
                    "pg_isready",
                    "-U",
                    databaseUser,
                    "-d",
                    databaseName);
            if (readinessExitCode == 0) {
                System.out.println("PostgreSQL service '" + databaseServiceName + "' is ready.");
                return;
            }
            Thread.sleep(DATABASE_READY_POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException(
                "PostgreSQL service '" + databaseServiceName + "' did not become ready within "
                        + DATABASE_READY_TIMEOUT.toSeconds() + " seconds.");
    }

    static String databaseName() {
        return databaseName(System.getenv());
    }

    static String databaseUser() {
        return databaseUser(System.getenv());
    }

    static String databaseName(Map<String, String> environment) {
        return CalendarToolVerification.environmentValueOrDefault(
                environment,
                POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE,
                DEFAULT_DATABASE_NAME);
    }

    static String databaseUser(Map<String, String> environment) {
        return CalendarToolVerification.environmentValueOrDefault(
                environment,
                POSTGRESQL_USER_ENVIRONMENT_VARIABLE,
                DEFAULT_DATABASE_USER);
    }

    static String[] composeSqlCommand(
            String databaseServiceName,
            String databaseUser,
            String databaseName,
            String sql) {
        return new String[] {
            "docker",
            "compose",
            "exec",
            "-T",
            databaseServiceName,
            "psql",
            "--tuples-only",
            "--no-align",
            "--set",
            "ON_ERROR_STOP=1",
            "--username",
            databaseUser,
            "--dbname",
            databaseName,
            "--command",
            sql
        };
    }
}
