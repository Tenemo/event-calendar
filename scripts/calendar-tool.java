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
import java.time.Instant;
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
    private static final String JAVA_COMMAND = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    private static final String MAVEN_WRAPPER_COMMAND = "mvnw";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String BROWSER_ENVIRONMENT_VARIABLE = "BROWSER";
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
    private static final String E2E_VERIFICATION_APPLICATION_SERVICE_NAME = "web-e2e-verification";
    private static final String E2E_VERIFICATION_DATABASE_SERVICE_NAME = "postgres-e2e-verification";
    private static final String E2E_VERIFICATION_PROFILE = "e2e-verification";
    private static final String E2E_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_APPLICATION_PORT";
    private static final String E2E_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_DATABASE_PORT";
    private static final String E2E_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_BASE_URL";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT = "9081";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT = "55432";
    private static final String DEFAULT_E2E_VERIFICATION_APPLICATION_PORT = "9082";
    private static final String DEFAULT_E2E_VERIFICATION_DATABASE_PORT = "55433";
    private static final String E2E_VERIFICATION_DATABASE_NAME = "calendar_e2e_verification";
    private static final String E2E_VERIFICATION_DATABASE_USER = "calendar_e2e_verification";
    private static final String E2E_VERIFICATION_DATABASE_PASSWORD = "calendar_e2e_verification";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_INVITATION_TOKEN =
            "bootstrap-verification-only-token-00000000000000000000000000000000";
    private static final String POSTGRESQL_CLIENT_IMAGE = "postgres:17";
    private static final String EXPECTED_FLYWAY_VERSION = "12";
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
        System.out.println("Java runtime:");
        runCommand("Java runtime check", JAVA_COMMAND, "-version");

        System.out.println("Maven:");
        runCommand("Maven Wrapper check", MAVEN_WRAPPER_COMMAND, "--version");

        System.out.println("Docker:");
        runCommand("Docker check", "docker", "--version");
        runCommand("Docker Compose check", "docker", "compose", "version");

        System.out.println("mise:");
        runCommand("mise check", "mise", "--version");
    }

    private static void startDatabase() throws IOException, InterruptedException {
        runCommand("PostgreSQL startup", "docker", "compose", "up", "-d", DATABASE_SERVICE_NAME);
        waitForDatabase();
    }

    private static void startDevelopmentServer() throws IOException, InterruptedException {
        runCommand(
                "Open Liberty dev mode",
                MAVEN_WRAPPER_COMMAND,
                "-Pliberty-dev",
                "generate-resources",
                "liberty:dev");
    }

    private static void packageApplication() throws IOException, InterruptedException {
        runCommand("Clean application package build", MAVEN_WRAPPER_COMMAND, "clean", "package");
    }

    private static void buildDockerImage() throws IOException, InterruptedException {
        runCommand(
                "Production image build",
                "docker",
                "compose",
                "--profile",
                "application",
                "build",
                "web");
    }

    private static void startDockerApplication() throws IOException, InterruptedException {
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
            requireLocalApplicationStoppedBeforeRestore();
            validateLocalBackupArchive(backupPath, DATABASE_SERVICE_NAME);
            restoreIntoComposeDatabase(
                    backupPath,
                    DATABASE_SERVICE_NAME,
                    databaseUser(),
                    databaseName(),
                    true);
        } else {
            System.err.println(
                    "Remote application state cannot be detected. Ensure every application instance is stopped before restoring.");
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
        Path backupPath = backupPostgres(".build/verification/restore-verification.dump");
        boolean startupAttempted = false;
        Throwable primaryFailure = null;

        try {
            startupAttempted = true;
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
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            try {
                if (startupAttempted) {
                    cleanUpComposeServices(
                            "Restore verification database cleanup",
                            primaryFailure,
                            Map.of(),
                            "restore-verification",
                            RESTORE_VERIFICATION_DATABASE_SERVICE_NAME);
                }
            } finally {
                Files.deleteIfExists(backupPath);
            }
        }
    }

    private static void requireLocalApplicationStoppedBeforeRestore() throws IOException, InterruptedException {
        String runningComposeServices = runCommandAndCapture(
                "Local application state check",
                "docker",
                "compose",
                "--profile",
                "application",
                "ps",
                "--services",
                "--status",
                "running",
                "web").trim();
        if (!runningComposeServices.isEmpty()) {
            throw new IllegalStateException(
                    "Stop the local Docker Compose web service before restoring PostgreSQL.");
        }

        URI localHealthUri = URI.create("http://localhost:" + applicationPort() + "/health");
        if (applicationEndpointResponds(localHealthUri)) {
            throw new IllegalStateException(
                    "Stop the local application responding at " + localHealthUri + " before restoring PostgreSQL.");
        }
    }

    private static boolean applicationEndpointResponds(URI healthUri) throws InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException exception) {
            return false;
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
        String timestamp = BACKUP_TIMESTAMP_FORMAT.format(Instant.now());
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

        HashMap<String, String> environment = new HashMap<>();
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
        String browserName = configuredBrowserName();
        String installationArguments = isLinux()
                ? "install --with-deps " + browserName
                : "install " + browserName;
        runCommand(
                "Playwright browser installation",
                MAVEN_WRAPPER_COMMAND,
                "-q",
                "-Dexec.classpathScope=test",
                "-Dexec.mainClass=com.microsoft.playwright.CLI",
                "-Dexec.args=" + installationArguments,
                "exec:java");
    }

    private static void runEndToEndTests() throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        buildDockerImage();
        verifySharedCalendarEndToEnd();
        verifyBootstrapRegistrationConcurrency(false, false);
    }

    private static void verifyBootstrapRegistrationConcurrency(boolean installBrowser)
            throws IOException, InterruptedException {
        verifyBootstrapRegistrationConcurrency(installBrowser, true);
    }

    private static void verifySharedCalendarEndToEnd() throws IOException, InterruptedException {
        String applicationPort = environmentValueOrDefault(
                E2E_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_E2E_VERIFICATION_APPLICATION_PORT);
        String databasePort = environmentValueOrDefault(
                E2E_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_E2E_VERIFICATION_DATABASE_PORT);
        String applicationBaseUrl = "http://localhost:" + applicationPort;
        Map<String, String> verificationEnvironment = new HashMap<>();
        verificationEnvironment.put(E2E_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE, applicationPort);
        verificationEnvironment.put(E2E_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE, databasePort);
        verificationEnvironment.put(E2E_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE, applicationBaseUrl);
        verificationEnvironment.put(APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE, applicationBaseUrl);
        verificationEnvironment.put(POSTGRESQL_HOST_ENVIRONMENT_VARIABLE, "localhost");
        verificationEnvironment.put(POSTGRESQL_PORT_ENVIRONMENT_VARIABLE, databasePort);
        verificationEnvironment.put(POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE, E2E_VERIFICATION_DATABASE_NAME);
        verificationEnvironment.put(POSTGRESQL_USER_ENVIRONMENT_VARIABLE, E2E_VERIFICATION_DATABASE_USER);
        verificationEnvironment.put(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE, E2E_VERIFICATION_DATABASE_PASSWORD);
        verificationEnvironment.put(BROWSER_ENVIRONMENT_VARIABLE, configuredBrowserName());

        boolean startupAttempted = false;
        Throwable primaryFailure = null;
        try {
            startupAttempted = true;
            runCommandWithEnvironment(
                    "End-to-end verification application startup",
                    verificationEnvironment,
                    "docker",
                    "compose",
                    "--profile",
                    E2E_VERIFICATION_PROFILE,
                    "up",
                    "-d",
                    "--force-recreate",
                    "--no-build",
                    E2E_VERIFICATION_DATABASE_SERVICE_NAME,
                    E2E_VERIFICATION_APPLICATION_SERVICE_NAME);
            waitForApplication(URI.create(applicationBaseUrl + "/health"));
            checkComposeDatabaseSchema(
                    E2E_VERIFICATION_DATABASE_SERVICE_NAME,
                    E2E_VERIFICATION_DATABASE_USER,
                    E2E_VERIFICATION_DATABASE_NAME);
            runCommandWithEnvironment(
                    "Playwright end-to-end tests",
                    verificationEnvironment,
                    MAVEN_WRAPPER_COMMAND,
                    "-Pe2e",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify");
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            primaryFailure = exception;
            showComposeLogs(
                    primaryFailure,
                    verificationEnvironment,
                    E2E_VERIFICATION_PROFILE,
                    E2E_VERIFICATION_APPLICATION_SERVICE_NAME,
                    E2E_VERIFICATION_DATABASE_SERVICE_NAME);
            throw exception;
        } finally {
            if (startupAttempted) {
                cleanUpComposeServices(
                        "End-to-end verification service cleanup",
                        primaryFailure,
                        verificationEnvironment,
                        E2E_VERIFICATION_PROFILE,
                        E2E_VERIFICATION_APPLICATION_SERVICE_NAME,
                        E2E_VERIFICATION_DATABASE_SERVICE_NAME);
            }
        }
    }

    private static void verifyBootstrapRegistrationConcurrency(boolean installBrowser, boolean buildImage)
            throws IOException, InterruptedException {
        if (installBrowser) {
            installPlaywrightBrowsers();
        }
        if (buildImage) {
            buildDockerImage();
        }

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
        Throwable primaryFailure = null;
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
            checkComposeDatabaseSchema(
                    BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME,
                    "calendar_bootstrap_verification",
                    "calendar_bootstrap_verification");
            runCommandWithEnvironment(
                    "Bootstrap registration concurrency verification",
                    verificationEnvironment,
                    MAVEN_WRAPPER_COMMAND,
                    "-Pbootstrap-concurrency-e2e",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify");
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            primaryFailure = exception;
            showComposeLogs(
                    primaryFailure,
                    verificationEnvironment,
                    BOOTSTRAP_VERIFICATION_PROFILE,
                    BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME,
                    BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME);
            throw exception;
        } finally {
            if (startupAttempted) {
                cleanUpComposeServices(
                        "Bootstrap verification service cleanup",
                        primaryFailure,
                        verificationEnvironment,
                        BOOTSTRAP_VERIFICATION_PROFILE,
                        BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME,
                        BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME);
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
        checkApplicationHealth();
        checkDatabaseSchema();
    }

    private static void checkDatabaseSchema() throws IOException, InterruptedException {
        checkComposeDatabaseSchema(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
    }

    private static void checkComposeDatabaseSchema(
            String databaseServiceName,
            String databaseUser,
            String databaseName) throws IOException, InterruptedException {
        String migrationState = runCommandAndCapture(
                "Flyway schema verification",
                "docker",
                "compose",
                "exec",
                "-T",
                databaseServiceName,
                "psql",
                "-U",
                databaseUser,
                "-d",
                databaseName,
                "--tuples-only",
                "--no-align",
                "--set",
                "ON_ERROR_STOP=1",
                "--command",
                "select (select count(*) from flyway_schema_history where not success)::text"
                        + " || '|' || coalesce((select version from flyway_schema_history"
                        + " where success and version is not null order by installed_rank desc limit 1), '');").trim();
        String expectedMigrationState = "0|" + EXPECTED_FLYWAY_VERSION;
        if (!expectedMigrationState.equals(migrationState)) {
            throw new IllegalStateException(
                    "Flyway schema verification expected no failed migrations and version "
                            + EXPECTED_FLYWAY_VERSION + ", but got '" + migrationState + "'.");
        }
        System.out.println("Flyway schema is current at version " + EXPECTED_FLYWAY_VERSION + ".");
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
        String responseBody = response.body().trim();
        if (statusCode != 200 || !responseBody.equals("ok")) {
            throw new IllegalStateException(
                    "Health check expected HTTP 200 with body 'ok', but got HTTP "
                            + statusCode + " with body '" + responseBody + "'.");
        }

        System.out.println("Health check returned HTTP 200 with body 'ok' from " + healthUri + ".");
    }

    private static URI applicationHealthUri() {
        String defaultApplicationBaseUrl = "http://localhost:" + applicationPort();
        String applicationBaseUrl = environmentValueOrDefault(
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                defaultApplicationBaseUrl);
        return URI.create(removeTrailingSlashes(applicationBaseUrl) + "/health");
    }

    private static String configuredBrowserName() {
        String browserName = environmentValueOrDefault(BROWSER_ENVIRONMENT_VARIABLE, "chromium")
                .toLowerCase(Locale.ROOT);
        return switch (browserName) {
            case "chromium", "firefox", "webkit" -> browserName;
            default -> throw new IllegalArgumentException(
                    "Unsupported Playwright browser '" + browserName + "'. Use chromium, firefox, or webkit.");
        };
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

    private static void showComposeLogs(
            Throwable primaryFailure,
            Map<String, String> environment,
            String profile,
            String... serviceNames) {
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--profile",
                profile,
                "logs",
                "--no-color"));
        command.addAll(List.of(serviceNames));
        try {
            int exitCode = runCommandForExitCode(
                    true,
                    environment,
                    command.toArray(String[]::new));
            if (exitCode != 0) {
                primaryFailure.addSuppressed(new IllegalStateException(
                        "Diagnostic logs failed with exit code " + exitCode + "."));
            }
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            primaryFailure.addSuppressed(exception);
        }
    }

    private static void cleanUpComposeServices(
            String description,
            Throwable primaryFailure,
            Map<String, String> environment,
            String profile,
            String... serviceNames) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--profile",
                profile,
                "rm",
                "--force",
                "--stop"));
        command.addAll(List.of(serviceNames));
        try {
            runCommandWithEnvironment(description, environment, command.toArray(String[]::new));
        } catch (IOException | InterruptedException | RuntimeException cleanupFailure) {
            if (primaryFailure == null) {
                throw cleanupFailure;
            }
            if (cleanupFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            primaryFailure.addSuppressed(cleanupFailure);
        }
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
                "Commands: setup, db, dev, package, e2e, verify-bootstrap-registration, verify-local, "
                        + "docker-build, docker-up, backup-postgres "
                        + "[output-file], restore-postgres <backup-file> <confirmed-database-name>, verify-backup-restore");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static String platformExecutableName(String commandName) {
        if (commandName.equals(MAVEN_WRAPPER_COMMAND)) {
            return isWindows() ? "mvnw.cmd" : "./mvnw";
        }

        return commandName;
    }
}
