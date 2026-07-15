import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CalendarTool {
    private static final Path PROJECT_DIRECTORY = Path.of("").toAbsolutePath().normalize();
    private static final Path LIBERTY_SHARED_POSTGRESQL_DIRECTORY =
            PROJECT_DIRECTORY.resolve("target/liberty/wlp/usr/shared/resources/postgresql");
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String PORT_ENVIRONMENT_VARIABLE = "PORT";
    private static final String POSTGRESQL_HOST_ENVIRONMENT_VARIABLE = "PGHOST";
    private static final String POSTGRESQL_PORT_ENVIRONMENT_VARIABLE = "PGPORT";
    private static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    private static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    private static final String POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE = "PGPASSWORD";
    private static final String POSTGRESQL_SSL_MODE_ENVIRONMENT_VARIABLE = "PGSSLMODE";
    private static final String DEFAULT_APPLICATION_PORT = "9080";
    private static final String DEFAULT_DATABASE_HOST = "localhost";
    private static final String DEFAULT_DATABASE_PORT = "5432";
    private static final String DEFAULT_DATABASE_NAME = "calendar";
    private static final String DEFAULT_DATABASE_USER = "calendar";
    private static final String DATABASE_SERVICE_NAME = "postgres";
    private static final String RESTORE_VERIFICATION_DATABASE_SERVICE_NAME = "postgres-restore-verification";
    private static final String RESTORE_VERIFICATION_DATABASE_NAME = "calendar_restore";
    private static final String RESTORE_VERIFICATION_DATABASE_USER = "calendar_restore";
    private static final String BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME =
            "postgres-bootstrap-verification";
    private static final String BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME =
            "web-bootstrap-verification";
    private static final String BOOTSTRAP_VERIFICATION_PROFILE = "bootstrap-verification";
    private static final String BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_APPLICATION_PORT";
    private static final String BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_DATABASE_PORT";
    private static final String BOOTSTRAP_VERIFICATION_INVITATION_TOKEN_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_INVITATION_TOKEN";
    private static final String BOOTSTRAP_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_BASE_URL";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT = "9081";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT = "55432";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_INVITATION_TOKEN =
            "bootstrap-verification-only-token-00000000000000000000000000000000";
    private static final String POSTGRESQL_CLIENT_IMAGE = "postgres:17";
    private static final String APPLICATION_IMAGE_NAME = "shared-calendar:local";
    private static final String EXEC_MAVEN_PLUGIN_VERSION = "3.6.3";
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration APPLICATION_READY_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration DATABASE_READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DATABASE_READY_POLL_INTERVAL = Duration.ofSeconds(2);

    public static void main(String[] arguments) throws Exception {
        requireProjectDirectory();

        if (arguments.length == 0) {
            printUsage();
            System.exit(1);
        }

        switch (arguments[0]) {
            case "check-toolchain" -> {
                requireArgumentCount(arguments, 1);
                checkToolchain();
            }
            case "prepare-liberty-dev" -> {
                requireArgumentCount(arguments, 1);
                prepareLibertyDev();
            }
            case "setup" -> {
                requireArgumentCount(arguments, 1);
                setup();
            }
            case "db" -> {
                requireArgumentCount(arguments, 1);
                startDatabase();
            }
            case "dev" -> {
                requireArgumentCount(arguments, 1);
                startDevelopmentServer();
            }
            case "package" -> {
                requireArgumentCount(arguments, 1);
                packageApplication();
            }
            case "install-playwright" -> {
                requireArgumentCount(arguments, 1);
                installPlaywrightBrowsers();
            }
            case "e2e" -> {
                requireArgumentCount(arguments, 1);
                runEndToEndTests();
            }
            case "verify-bootstrap-registration" -> {
                requireArgumentCount(arguments, 1);
                verifyBootstrapRegistrationConcurrency(true);
            }
            case "wait-for-app" -> {
                requireArgumentCount(arguments, 1);
                waitForApplication();
            }
            case "verify-local" -> {
                requireArgumentCount(arguments, 1);
                verifyLocal();
            }
            case "verify-running-app" -> {
                requireArgumentCount(arguments, 1);
                verifyRunningApplication();
            }
            case "docker-build" -> {
                requireArgumentCount(arguments, 1);
                buildDockerImage();
            }
            case "docker-up" -> {
                requireArgumentCount(arguments, 1);
                startDockerApplication();
            }
            case "backup-postgres" -> {
                requireArgumentRange(arguments, 1, 2);
                backupPostgres(arguments.length == 2 ? arguments[1] : null);
            }
            case "restore-postgres" -> {
                requireArgumentCount(arguments, 3);
                restorePostgres(arguments[1], arguments[2]);
            }
            case "verify-backup-restore" -> {
                requireArgumentCount(arguments, 1);
                verifyBackupRestore();
            }
            default -> {
                System.err.println("Unknown command: " + arguments[0]);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void setup() throws IOException, InterruptedException {
        checkToolchain();
        prepareLibertyDev();
    }

    private static void checkToolchain() throws IOException, InterruptedException {
        System.out.println("Java runtime:");
        runCommand("Java runtime check", "java", "-version");

        System.out.println("Maven:");
        runCommand("Maven check", "mvn", "--version");

        System.out.println("Docker:");
        runCommand("Docker check", "docker", "--version");
        runCommand("Docker Compose check", "docker", "compose", "version");

        System.out.println("mise:");
        runCommand("mise check", "mise", "--version");
    }

    private static void prepareLibertyDev() throws IOException, InterruptedException {
        Files.createDirectories(LIBERTY_SHARED_POSTGRESQL_DIRECTORY);
        runCommand("Liberty resource preparation", "mvn", "-q", "generate-resources");
    }

    private static void startDatabase() throws IOException, InterruptedException {
        runCommand("PostgreSQL startup", "docker", "compose", "up", "-d", DATABASE_SERVICE_NAME);
        waitForDatabase();
    }

    private static void startDevelopmentServer() throws IOException, InterruptedException {
        prepareLibertyDev();
        runCommand("Open Liberty dev mode", "mvn", "liberty:dev");
    }

    private static void packageApplication() throws IOException, InterruptedException {
        runCommand("Application package build", "mvn", "package");
    }

    private static void buildDockerImage() throws IOException, InterruptedException {
        runCommand("Production image build", "docker", "build", "--tag", APPLICATION_IMAGE_NAME, ".");
    }

    private static void startDockerApplication() throws IOException, InterruptedException {
        startDatabase();
        runCommand(
                "Production container startup",
                "docker",
                "compose",
                "--profile",
                "application",
                "up",
                "-d",
                "--build",
                "web");
        waitForApplication();
    }

    private static Path backupPostgres(String configuredOutputPath) throws IOException, InterruptedException {
        Path outputPath = configuredOutputPath == null
                ? defaultBackupPath()
                : projectRelativePath(configuredOutputPath);
        Path parentDirectory = outputPath.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
        Path temporaryOutputPath = Files.createTempFile(
                parentDirectory,
                outputPath.getFileName().toString() + ".",
                ".partial");

        try {
            if (usesLocalComposeDatabase()) {
                runCommandToFile(
                        "Local PostgreSQL backup",
                        temporaryOutputPath,
                        Map.of(),
                        "docker",
                        "compose",
                        "exec",
                        "-T",
                        DATABASE_SERVICE_NAME,
                        "pg_dump",
                        "--username",
                        databaseUser(),
                        "--dbname",
                        databaseName(),
                        "--format=custom",
                        "--no-owner",
                        "--no-privileges");
            } else {
                runCommandToFile(
                        "Remote PostgreSQL backup",
                        temporaryOutputPath,
                        remoteDatabaseEnvironment(),
                        remotePostgresqlClientCommand(
                                false,
                                "pg_dump",
                                "--host",
                                databaseHost(),
                                "--port",
                                databasePort(),
                                "--username",
                                databaseUser(),
                                "--dbname",
                                databaseName(),
                                "--format=custom",
                                "--no-owner",
                                "--no-privileges"));
            }

            if (Files.size(temporaryOutputPath) == 0) {
                throw new IllegalStateException("PostgreSQL backup did not produce a non-empty archive.");
            }
            replaceBackupAtomically(temporaryOutputPath, outputPath);
        } finally {
            Files.deleteIfExists(temporaryOutputPath);
        }

        System.out.println("Backup written to " + outputPath + ".");
        return outputPath;
    }

    private static void replaceBackupAtomically(Path temporaryOutputPath, Path outputPath) throws IOException {
        try {
            Files.move(
                    temporaryOutputPath,
                    outputPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryOutputPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restorePostgres(String configuredBackupPath, String confirmedDatabaseName)
            throws IOException, InterruptedException {
        Path backupPath = requireBackupPath(configuredBackupPath);
        if (!databaseName().equals(confirmedDatabaseName)) {
            throw new IllegalArgumentException(
                    "Restore confirmation must exactly match PGDATABASE ('" + databaseName() + "').");
        }

        if (usesLocalComposeDatabase()) {
            validateLocalBackupArchive(backupPath, DATABASE_SERVICE_NAME);
            restoreIntoComposeDatabase(
                    backupPath,
                    DATABASE_SERVICE_NAME,
                    databaseUser(),
                    databaseName(),
                    true);
        } else {
            validateRemoteBackupArchive(backupPath);
            runCommandWithInput(
                    "Remote PostgreSQL restore",
                    backupPath,
                    remoteDatabaseEnvironment(),
                    remotePostgresqlClientCommand(
                            true,
                            "pg_restore",
                            "--host",
                            databaseHost(),
                            "--port",
                            databasePort(),
                            "--username",
                            databaseUser(),
                            "--dbname",
                            databaseName(),
                            "--clean",
                            "--if-exists",
                            "--no-owner",
                            "--no-privileges",
                            "--single-transaction",
                            "--exit-on-error"));
        }

        System.out.println("Restore completed for database '" + databaseName() + "'.");
    }

    private static void verifyBackupRestore() throws IOException, InterruptedException {
        if (!usesLocalComposeDatabase()) {
            throw new IllegalStateException("Backup/restore verification requires the local Docker Compose database.");
        }

        startDatabase();
        String sourceFingerprint = databaseFingerprint(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
        Path backupPath = backupPostgres("target/backups/restore-verification.dump");
        boolean verificationServiceStarted = false;

        try {
            runCommand(
                    "Restore verification database startup",
                    "docker",
                    "compose",
                    "--profile",
                    "restore-verification",
                    "up",
                    "-d",
                    "--force-recreate",
                    RESTORE_VERIFICATION_DATABASE_SERVICE_NAME);
            verificationServiceStarted = true;
            waitForComposeDatabase(
                    RESTORE_VERIFICATION_DATABASE_SERVICE_NAME,
                    RESTORE_VERIFICATION_DATABASE_USER,
                    RESTORE_VERIFICATION_DATABASE_NAME);
            validateLocalBackupArchive(backupPath, RESTORE_VERIFICATION_DATABASE_SERVICE_NAME);
            restoreIntoComposeDatabase(
                    backupPath,
                    RESTORE_VERIFICATION_DATABASE_SERVICE_NAME,
                    RESTORE_VERIFICATION_DATABASE_USER,
                    RESTORE_VERIFICATION_DATABASE_NAME,
                    false);

            String restoredFingerprint = databaseFingerprint(
                    RESTORE_VERIFICATION_DATABASE_SERVICE_NAME,
                    RESTORE_VERIFICATION_DATABASE_USER,
                    RESTORE_VERIFICATION_DATABASE_NAME);
            if (!sourceFingerprint.equals(restoredFingerprint)) {
                throw new IllegalStateException(
                        "Restored database row counts differ from the source database. Source: "
                                + sourceFingerprint + "; restored: " + restoredFingerprint + ".");
            }

            System.out.println("Backup and restore verification passed with matching schema and row counts.");
        } finally {
            if (verificationServiceStarted) {
                int stopExitCode = runCommandForExitCode(
                        true,
                        "docker",
                        "compose",
                        "--profile",
                        "restore-verification",
                        "stop",
                        RESTORE_VERIFICATION_DATABASE_SERVICE_NAME);
                if (stopExitCode != 0) {
                    System.err.println("Restore verification database did not stop cleanly.");
                }
            }
        }
    }

    private static void validateLocalBackupArchive(Path backupPath, String databaseServiceName)
            throws IOException, InterruptedException {
        runCommandWithInput(
                "PostgreSQL backup archive validation",
                backupPath,
                Map.of(),
                "docker",
                "compose",
                "exec",
                "-T",
                databaseServiceName,
                "pg_restore",
                "--list");
    }

    private static void validateRemoteBackupArchive(Path backupPath) throws IOException, InterruptedException {
        runCommandWithInput(
                "PostgreSQL backup archive validation",
                backupPath,
                remoteDatabaseEnvironment(),
                remotePostgresqlClientCommand(true, "pg_restore", "--list"));
    }

    private static void restoreIntoComposeDatabase(
            Path backupPath,
            String databaseServiceName,
            String databaseUser,
            String databaseName,
            boolean cleanExistingObjects) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "exec",
                "-T",
                databaseServiceName,
                "pg_restore",
                "--username",
                databaseUser,
                "--dbname",
                databaseName));
        if (cleanExistingObjects) {
            command.add("--clean");
            command.add("--if-exists");
        }
        command.add("--no-owner");
        command.add("--no-privileges");
        command.add("--single-transaction");
        command.add("--exit-on-error");

        runCommandWithInput(
                "PostgreSQL restore into " + databaseServiceName,
                backupPath,
                Map.of(),
                command.toArray(String[]::new));
    }

    private static String databaseFingerprint(String databaseServiceName, String databaseUser, String databaseName)
            throws IOException, InterruptedException {
        String rowCountQuery = "select json_build_object("
                + "'app_user', (select count(*) from app_user), "
                + "'calendar', (select count(*) from calendar), "
                + "'calendar_member', (select count(*) from calendar_member), "
                + "'calendar_event', (select count(*) from calendar_event), "
                + "'app_invitation', (select count(*) from app_invitation), "
                + "'app_registration_bootstrap', (select count(*) from app_registration_bootstrap), "
                + "'audit_log', (select count(*) from audit_log), "
                + "'flyway_schema_history', (select count(*) from flyway_schema_history))::text;";
        return runCommandAndCapture(
                "PostgreSQL row-count fingerprint",
                "docker",
                "compose",
                "exec",
                "-T",
                databaseServiceName,
                "psql",
                "--tuples-only",
                "--no-align",
                "--username",
                databaseUser,
                "--dbname",
                databaseName,
                "--command",
                rowCountQuery).trim();
    }

    private static Path defaultBackupPath() {
        String timestamp = BACKUP_TIMESTAMP_FORMAT.format(java.time.Instant.now());
        return PROJECT_DIRECTORY.resolve("target/backups/shared-calendar-" + timestamp + ".dump");
    }

    private static Path projectRelativePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        return (path.isAbsolute() ? path : PROJECT_DIRECTORY.resolve(path)).toAbsolutePath().normalize();
    }

    private static Path requireBackupPath(String configuredPath) {
        Path backupPath = projectRelativePath(configuredPath);
        if (!Files.isRegularFile(backupPath)) {
            throw new IllegalArgumentException("Backup file does not exist: " + backupPath + ".");
        }
        return backupPath;
    }

    private static boolean usesLocalComposeDatabase() {
        String normalizedHost = databaseHost().toLowerCase(Locale.ROOT);
        return normalizedHost.equals("localhost")
                || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("::1")
                || normalizedHost.equals(DATABASE_SERVICE_NAME);
    }

    private static Map<String, String> remoteDatabaseEnvironment() {
        String password = System.getenv(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("PGPASSWORD is required for remote backup and restore.");
        }

        java.util.HashMap<String, String> environment = new java.util.HashMap<>();
        environment.put(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE, password);
        String sslMode = System.getenv(POSTGRESQL_SSL_MODE_ENVIRONMENT_VARIABLE);
        if (sslMode != null && !sslMode.isBlank()) {
            environment.put(POSTGRESQL_SSL_MODE_ENVIRONMENT_VARIABLE, sslMode.trim());
        }
        return Map.copyOf(environment);
    }

    private static String[] remotePostgresqlClientCommand(boolean readsStandardInput, String... postgresqlCommand) {
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm"));
        if (readsStandardInput) {
            command.add("--interactive");
        }
        command.add("--env");
        command.add(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE);
        String sslMode = System.getenv(POSTGRESQL_SSL_MODE_ENVIRONMENT_VARIABLE);
        if (sslMode != null && !sslMode.isBlank()) {
            command.add("--env");
            command.add(POSTGRESQL_SSL_MODE_ENVIRONMENT_VARIABLE);
        }
        command.add(POSTGRESQL_CLIENT_IMAGE);
        command.addAll(List.of(postgresqlCommand));
        return command.toArray(String[]::new);
    }

    private static void installPlaywrightBrowsers() throws IOException, InterruptedException {
        runCommand(
                "Playwright browser installation",
                "mvn",
                "-q",
                "-Dexec.classpathScope=test",
                "-Dexec.mainClass=com.microsoft.playwright.CLI",
                "-Dexec.args=install",
                "org.codehaus.mojo:exec-maven-plugin:" + EXEC_MAVEN_PLUGIN_VERSION + ":java");
    }

    private static void runEndToEndTests() throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        runCommand("Playwright end-to-end tests", "mvn", "verify", "-Pe2e");
        verifyBootstrapRegistrationConcurrency(false);
    }

    private static void verifyBootstrapRegistrationConcurrency(boolean installBrowser)
            throws IOException, InterruptedException {
        if (installBrowser) {
            installPlaywrightBrowsers();
        }
        buildDockerImage();

        String applicationPort = environmentValueOrDefault(
                BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT);
        String databasePort = environmentValueOrDefault(
                BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT);
        String applicationBaseUrl = "http://localhost:" + applicationPort;
        Map<String, String> verificationEnvironment = new HashMap<>();
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                applicationPort);
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                databasePort);
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_INVITATION_TOKEN_ENVIRONMENT_VARIABLE,
                environmentValueOrDefault(
                        BOOTSTRAP_VERIFICATION_INVITATION_TOKEN_ENVIRONMENT_VARIABLE,
                        DEFAULT_BOOTSTRAP_VERIFICATION_INVITATION_TOKEN));
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                applicationBaseUrl);

        boolean startupAttempted = false;
        try {
            startupAttempted = true;
            runCommandWithEnvironment(
                    "Bootstrap verification application startup",
                    verificationEnvironment,
                    "docker",
                    "compose",
                    "--profile",
                    BOOTSTRAP_VERIFICATION_PROFILE,
                    "up",
                    "-d",
                    "--force-recreate",
                    "--no-build",
                    BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME,
                    BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME);
            waitForApplication(URI.create(applicationBaseUrl + "/health"));
            runCommandWithEnvironment(
                    "Bootstrap registration concurrency verification",
                    verificationEnvironment,
                    "mvn",
                    "-Pbootstrap-concurrency-e2e",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify");
        } catch (IOException | InterruptedException | RuntimeException exception) {
            runCommandForExitCode(
                    true,
                    verificationEnvironment,
                    "docker",
                    "compose",
                    "--profile",
                    BOOTSTRAP_VERIFICATION_PROFILE,
                    "logs",
                    "--no-color",
                    BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME,
                    BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME);
            throw exception;
        } finally {
            if (startupAttempted) {
                int cleanupExitCode = runCommandForExitCode(
                        true,
                        verificationEnvironment,
                        "docker",
                        "compose",
                        "--profile",
                        BOOTSTRAP_VERIFICATION_PROFILE,
                        "rm",
                        "--force",
                        "--stop",
                        BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME,
                        BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME);
                if (cleanupExitCode != 0) {
                    throw new IllegalStateException(
                            "Bootstrap verification services did not clean up successfully.");
                }
            }
        }
    }

    private static void waitForApplication() throws InterruptedException {
        waitForApplication(applicationHealthUri());
    }

    private static void waitForApplication(URI healthUri) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + APPLICATION_READY_TIMEOUT.toNanos();
        Exception lastHealthCheckFailure = null;

        while (System.nanoTime() < deadlineNanos) {
            try {
                checkApplicationHealth(healthUri);
                return;
            } catch (IOException | IllegalStateException exception) {
                lastHealthCheckFailure = exception;
            }

            Thread.sleep(APPLICATION_READY_POLL_INTERVAL.toMillis());
        }

        throw new IllegalStateException(
                "Application did not become healthy within " + APPLICATION_READY_TIMEOUT.toSeconds() + " seconds.",
                lastHealthCheckFailure);
    }

    private static void verifyLocal() throws IOException, InterruptedException {
        packageApplication();
        startDatabase();
        verifyRunningApplication();
    }

    private static void verifyRunningApplication() throws IOException, InterruptedException {
        checkApplicationHealth();
        checkDatabaseConnection();
        checkDatabaseSchema();
    }

    private static void checkDatabaseConnection() throws IOException, InterruptedException {
        runCommand(
                "PostgreSQL connection check",
                "docker",
                "compose",
                "exec",
                "-T",
                DATABASE_SERVICE_NAME,
                "psql",
                "-U",
                databaseUser(),
                "-d",
                databaseName(),
                "-c",
                "select current_database(), current_user;");
    }

    private static void checkDatabaseSchema() throws IOException, InterruptedException {
        runCommand(
                "PostgreSQL table check",
                "docker",
                "compose",
                "exec",
                "-T",
                DATABASE_SERVICE_NAME,
                "psql",
                "-U",
                databaseUser(),
                "-d",
                databaseName(),
                "-c",
                "\\dt");
        runCommand(
                "Flyway migration history check",
                "docker",
                "compose",
                "exec",
                "-T",
                DATABASE_SERVICE_NAME,
                "psql",
                "-U",
                databaseUser(),
                "-d",
                databaseName(),
                "-c",
                "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;");
    }

    private static void waitForDatabase() throws IOException, InterruptedException {
        waitForComposeDatabase(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
    }

    private static void waitForComposeDatabase(String databaseServiceName, String databaseUser, String databaseName)
            throws IOException, InterruptedException {
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

    private static void checkApplicationHealth() throws IOException, InterruptedException {
        checkApplicationHealth(applicationHealthUri());
    }

    private static void checkApplicationHealth(URI healthUri) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IOException(
                    "Health check failed for " + healthUri + ". Start the app with 'mise run dev' before running this check.",
                    exception);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Health check failed with HTTP " + statusCode + ".");
        }

        System.out.println("Health check returned HTTP " + statusCode + " from " + healthUri + ".");
    }

    private static URI applicationHealthUri() {
        String defaultApplicationBaseUrl = "http://localhost:" + applicationPort();
        String applicationBaseUrl = environmentValueOrDefault(
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                defaultApplicationBaseUrl);
        return URI.create(removeTrailingSlashes(applicationBaseUrl) + "/health");
    }

    private static String applicationPort() {
        return environmentValueOrDefault(PORT_ENVIRONMENT_VARIABLE, DEFAULT_APPLICATION_PORT);
    }

    private static String databaseName() {
        return environmentValueOrDefault(POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_NAME);
    }

    private static String databaseUser() {
        return environmentValueOrDefault(POSTGRESQL_USER_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_USER);
    }

    private static String databaseHost() {
        return environmentValueOrDefault(POSTGRESQL_HOST_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_HOST);
    }

    private static String databasePort() {
        return environmentValueOrDefault(POSTGRESQL_PORT_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_PORT);
    }

    private static String environmentValueOrDefault(String variableName, String defaultValue) {
        String configuredValue = System.getenv(variableName);
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }

        return configuredValue.trim();
    }

    private static String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }

        return normalizedValue;
    }

    private static void requireProjectDirectory() {
        if (!Files.isRegularFile(PROJECT_DIRECTORY.resolve("pom.xml"))) {
            throw new IllegalStateException("Run this tool from the repository root.");
        }
    }

    private static void runCommand(String description, String... command)
            throws IOException, InterruptedException {
        int exitCode = runCommandForExitCode(true, command);
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    private static void runCommandWithEnvironment(
            String description,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        int exitCode = runCommandForExitCode(true, environment, command);
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    private static void runCommandToFile(
            String description,
            Path outputPath,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectOutput(outputPath.toFile());
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        int exitCode = startAndWait(processBuilder);
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    private static void runCommandWithInput(
            String description,
            Path inputPath,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectInput(inputPath.toFile());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        int exitCode = startAndWait(processBuilder);
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    private static String runCommandAndCapture(String description, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = startProcess(processBuilder);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
        return output;
    }

    private static int runCommandForExitCode(boolean inheritOutput, String... command)
            throws IOException, InterruptedException {
        return runCommandForExitCode(inheritOutput, Map.of(), command);
    }

    private static int runCommandForExitCode(
            boolean inheritOutput,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.environment().putAll(environment);

        if (inheritOutput) {
            processBuilder.inheritIO();
        } else {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }

        return startAndWait(processBuilder);
    }

    private static ProcessBuilder commandProcessBuilder(String... command) {
        List<String> commandLine = new ArrayList<>(Arrays.asList(command));
        commandLine.set(0, platformExecutableName(commandLine.getFirst()));
        return new ProcessBuilder(commandLine).directory(PROJECT_DIRECTORY.toFile());
    }

    private static int startAndWait(ProcessBuilder processBuilder) throws IOException, InterruptedException {
        return startProcess(processBuilder).waitFor();
    }

    private static Process startProcess(ProcessBuilder processBuilder) throws IOException {
        try {
            return processBuilder.start();
        } catch (IOException exception) {
            String executableName = processBuilder.command().isEmpty()
                    ? "unknown"
                    : processBuilder.command().getFirst();
            throw new IOException("Required command '" + executableName + "' was not found on PATH.", exception);
        }
    }

    private static void requireArgumentCount(String[] arguments, int expectedCount) {
        requireArgumentRange(arguments, expectedCount, expectedCount);
    }

    private static void requireArgumentRange(String[] arguments, int minimumCount, int maximumCount) {
        if (arguments.length < minimumCount || arguments.length > maximumCount) {
            System.err.println("Invalid arguments for command '" + arguments[0] + "'.");
            printUsage();
            System.exit(1);
        }
    }

    private static void printUsage() {
        String executableName = isWindows() ? "java scripts\\calendar-tool.java" : "java scripts/calendar-tool.java";
        System.err.println("Usage: " + executableName + " <command> [arguments]");
        System.err.println(
                "Commands: check-toolchain, prepare-liberty-dev, setup, db, dev, package, install-playwright, e2e, "
                        + "verify-bootstrap-registration, wait-for-app, verify-local, verify-running-app, "
                        + "docker-build, docker-up, backup-postgres "
                        + "[output-file], restore-postgres <backup-file> <confirmed-database-name>, verify-backup-restore");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String platformExecutableName(String commandName) {
        if (isWindows() && commandName.equals("mvn")) {
            return "mvn.cmd";
        }

        return commandName;
    }
}
