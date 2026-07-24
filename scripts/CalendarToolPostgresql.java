import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CalendarToolPostgresql extends CalendarToolProcessRunner {
    static final String POSTGRESQL_HOST_ENVIRONMENT_VARIABLE = "PGHOST";
    static final String POSTGRESQL_PORT_ENVIRONMENT_VARIABLE = "PGPORT";
    static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    static final String POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE = "PGPASSWORD";
    static final String POSTGRESQL_SSL_MODE_ENVIRONMENT_VARIABLE = "PGSSLMODE";
    static final String DATABASE_SERVICE_NAME = "postgres";

    private static final String PORT_ENVIRONMENT_VARIABLE = "PORT";
    private static final String DEFAULT_APPLICATION_PORT = "9080";
    private static final String DEFAULT_DATABASE_HOST = "localhost";
    private static final String DEFAULT_DATABASE_PORT = "5432";
    private static final String DEFAULT_DATABASE_NAME = "calendar";
    private static final String DEFAULT_DATABASE_USER = "calendar";
    private static final String BACKUP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE =
            "BACKUP_VERIFICATION_APPLICATION_PORT";
    private static final String DEFAULT_BACKUP_VERIFICATION_APPLICATION_PORT = "9083";
    private static final String BACKUP_VERIFICATION_APPLICATION_SERVICE_NAME = "web-backup-verification";
    private static final String BACKUP_VERIFICATION_DATABASE_SERVICE_NAME = "postgres-backup-verification";
    private static final String BACKUP_VERIFICATION_DATABASE_NAME = "calendar_backup_verification";
    private static final String BACKUP_VERIFICATION_DATABASE_USER = "calendar_backup_verification";
    private static final String BACKUP_VERIFICATION_PROFILE = "backup-verification";
    private static final String RESTORE_VERIFICATION_DATABASE_SERVICE_NAME = "postgres-restore-verification";
    private static final String RESTORE_VERIFICATION_DATABASE_NAME = "calendar_restore";
    private static final String RESTORE_VERIFICATION_DATABASE_USER = "calendar_restore";
    private static final String RESTORE_VERIFICATION_PROFILE = "restore-verification";
    private static final String EXPECTED_FLYWAY_VERSION = "12";
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Duration DATABASE_READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DATABASE_READY_POLL_INTERVAL = Duration.ofSeconds(2);

    private CalendarToolPostgresql() {}

    static void startDatabase() throws IOException, InterruptedException {
        runCommand("PostgreSQL startup", "docker", "compose", "up", "-d", DATABASE_SERVICE_NAME);
        waitForDatabase();
    }

    static Path backupPostgres(String configuredOutputPath) throws IOException, InterruptedException {
        if (usesLocalComposeDatabase()) {
            return writeBackup(
                    configuredOutputPath,
                    "Local PostgreSQL backup",
                    Map.of(),
                    composeBackupCommand(DATABASE_SERVICE_NAME, databaseUser(), databaseName()));
        }

        return writeBackup(
                configuredOutputPath,
                "Remote PostgreSQL backup",
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

    static void restorePostgres(String configuredBackupPath, String confirmedDatabaseName)
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

    static void verifyBackupRestore() throws IOException, InterruptedException {
        BackupVerificationEndpoints endpoints = backupVerificationEndpoints(System.getenv());
        Map<String, String> verificationEnvironment = Map.of(
                BACKUP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                endpoints.applicationPort());
        Path backupPath = projectRelativePath(".build/verification/restore-verification.dump");
        boolean startupAttempted = false;
        Throwable primaryFailure = null;

        try {
            startupAttempted = true;
            runCommandWithEnvironment(
                    "Backup verification source application startup",
                    verificationEnvironment,
                    backupVerificationSourceStartupCommand());
            CalendarToolVerification.waitForApplication(endpoints.healthUri());
            checkComposeDatabaseSchema(
                    BACKUP_VERIFICATION_DATABASE_SERVICE_NAME,
                    BACKUP_VERIFICATION_DATABASE_USER,
                    BACKUP_VERIFICATION_DATABASE_NAME);
            verifyBackupVerificationApplicationLogs(verificationEnvironment);
            runCommandWithEnvironment(
                    "Backup verification application shutdown",
                    verificationEnvironment,
                    backupVerificationApplicationStopCommand());

            String sourceFingerprint = databaseFingerprint(
                    BACKUP_VERIFICATION_DATABASE_SERVICE_NAME,
                    BACKUP_VERIFICATION_DATABASE_USER,
                    BACKUP_VERIFICATION_DATABASE_NAME);
            writeBackup(
                    backupPath.toString(),
                    "Backup verification PostgreSQL backup",
                    verificationEnvironment,
                    composeBackupCommand(
                            BACKUP_VERIFICATION_DATABASE_SERVICE_NAME,
                            BACKUP_VERIFICATION_DATABASE_USER,
                            BACKUP_VERIFICATION_DATABASE_NAME));

            runCommand(
                    "Restore verification database startup",
                    restoreVerificationDatabaseStartupCommand());
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
                    cleanUpBackupRestoreVerificationServices(primaryFailure, verificationEnvironment);
                }
            } finally {
                Files.deleteIfExists(backupPath);
            }
        }
    }

    static BackupVerificationEndpoints backupVerificationEndpoints(Map<String, String> environment) {
        String applicationPort = CalendarToolVerification.environmentPortValue(
                environment,
                BACKUP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BACKUP_VERIFICATION_APPLICATION_PORT);
        return new BackupVerificationEndpoints(
                applicationPort,
                URI.create("http://localhost:" + applicationPort + "/health"));
    }

    record BackupVerificationEndpoints(String applicationPort, URI healthUri) {}

    static String[] backupVerificationSourceStartupCommand() {
        return new String[] {
            "docker",
            "compose",
            "--profile",
            BACKUP_VERIFICATION_PROFILE,
            "up",
            "-d",
            "--force-recreate",
            "--no-build",
            BACKUP_VERIFICATION_DATABASE_SERVICE_NAME,
            BACKUP_VERIFICATION_APPLICATION_SERVICE_NAME
        };
    }

    static String[] backupVerificationApplicationStopCommand() {
        return new String[] {
            "docker",
            "compose",
            "--profile",
            BACKUP_VERIFICATION_PROFILE,
            "stop",
            BACKUP_VERIFICATION_APPLICATION_SERVICE_NAME
        };
    }

    static String[] backupVerificationApplicationLogsCommand() {
        return new String[] {
            "docker",
            "compose",
            "--profile",
            BACKUP_VERIFICATION_PROFILE,
            "logs",
            "--no-color",
            BACKUP_VERIFICATION_APPLICATION_SERVICE_NAME
        };
    }

    static String[] restoreVerificationDatabaseStartupCommand() {
        return new String[] {
            "docker",
            "compose",
            "--profile",
            RESTORE_VERIFICATION_PROFILE,
            "up",
            "-d",
            "--force-recreate",
            RESTORE_VERIFICATION_DATABASE_SERVICE_NAME
        };
    }

    static String[] backupRestoreCleanupCommand() {
        return new String[] {
            "docker",
            "compose",
            "--profile",
            BACKUP_VERIFICATION_PROFILE,
            "--profile",
            RESTORE_VERIFICATION_PROFILE,
            "rm",
            "--force",
            "--stop",
            BACKUP_VERIFICATION_APPLICATION_SERVICE_NAME,
            BACKUP_VERIFICATION_DATABASE_SERVICE_NAME,
            RESTORE_VERIFICATION_DATABASE_SERVICE_NAME
        };
    }

    static String[] composeBackupCommand(
            String databaseServiceName,
            String databaseUser,
            String databaseName) {
        return new String[] {
            "docker",
            "compose",
            "exec",
            "-T",
            databaseServiceName,
            "pg_dump",
            "--username",
            databaseUser,
            "--dbname",
            databaseName,
            "--format=custom",
            "--no-owner",
            "--no-privileges"
        };
    }

    static void checkDatabaseSchema() throws IOException, InterruptedException {
        checkComposeDatabaseSchema(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
    }

    static void checkComposeDatabaseSchema(
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

    static void waitForDatabase() throws IOException, InterruptedException {
        waitForComposeDatabase(DATABASE_SERVICE_NAME, databaseUser(), databaseName());
    }

    static void waitForComposeDatabase(String databaseServiceName, String databaseUser, String databaseName)
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

    static String postgresqlClientImageReference(String composeFileContents) {
        String canonicalReference = null;
        for (String line : composeFileContents.lines().toList()) {
            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("image:")) {
                continue;
            }

            String imageReference = trimmedLine.substring("image:".length()).trim();
            if (imageReference.length() >= 2
                    && ((imageReference.startsWith("\"") && imageReference.endsWith("\""))
                            || (imageReference.startsWith("'") && imageReference.endsWith("'")))) {
                imageReference = imageReference.substring(1, imageReference.length() - 1);
            }
            if (!imageReference.startsWith("postgres:")) {
                continue;
            }
            if (canonicalReference != null && !canonicalReference.equals(imageReference)) {
                throw new IllegalStateException(
                        "All PostgreSQL Docker Compose services must use one canonical image reference.");
            }
            canonicalReference = imageReference;
        }

        if (canonicalReference == null) {
            throw new IllegalStateException("docker-compose.yml does not declare a PostgreSQL image.");
        }
        if (!canonicalReference.matches("postgres:[0-9]+\\.[0-9]+@sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "The PostgreSQL Docker Compose image must use a patch version and SHA-256 digest.");
        }
        return canonicalReference;
    }

    static String databaseName() {
        return environmentValueOrDefault(POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_NAME);
    }

    static String databaseUser() {
        return environmentValueOrDefault(POSTGRESQL_USER_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_USER);
    }

    private static Path writeBackup(
            String configuredOutputPath,
            String description,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
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
            runCommandToFile(description, temporaryOutputPath, environment, command);
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

    private static String[] remotePostgresqlClientCommand(boolean readsStandardInput, String... postgresqlCommand)
            throws IOException {
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
        command.add(postgresqlClientImageReference(Files.readString(PROJECT_DIRECTORY.resolve("docker-compose.yml"))));
        command.addAll(List.of(postgresqlCommand));
        return command.toArray(String[]::new);
    }

    private static String databaseHost() {
        return environmentValueOrDefault(POSTGRESQL_HOST_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_HOST);
    }

    private static String databasePort() {
        return environmentPortValue(POSTGRESQL_PORT_ENVIRONMENT_VARIABLE, DEFAULT_DATABASE_PORT);
    }

    private static String applicationPort() {
        return environmentPortValue(PORT_ENVIRONMENT_VARIABLE, DEFAULT_APPLICATION_PORT);
    }

    private static String environmentValueOrDefault(String variableName, String defaultValue) {
        String configuredValue = System.getenv(variableName);
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }
        return configuredValue.trim();
    }

    private static String environmentPortValue(String variableName, String defaultValue) {
        String configuredValue = environmentValueOrDefault(variableName, defaultValue);
        int port;
        try {
            port = Integer.parseInt(configuredValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(variableName + " must be a port number from 1 through 65535.", exception);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(variableName + " must be a port number from 1 through 65535.");
        }
        return Integer.toString(port);
    }

    private static void verifyBackupVerificationApplicationLogs(Map<String, String> verificationEnvironment)
            throws IOException, InterruptedException {
        CapturedCommandOutput applicationLogOutput = runCommandAndCaptureCombinedOutput(
                verificationEnvironment,
                backupVerificationApplicationLogsCommand());
        if (applicationLogOutput.exitCode() != 0) {
            throw new IllegalStateException(
                    "Backup verification application log check failed with exit code "
                            + applicationLogOutput.exitCode() + ".");
        }
        CalendarToolVerification.validateApplicationRuntimeLogs(applicationLogOutput.output());
    }

    private static void cleanUpBackupRestoreVerificationServices(
            Throwable primaryFailure,
            Map<String, String> verificationEnvironment) throws IOException, InterruptedException {
        try {
            runCommandWithEnvironment(
                    "Backup and restore verification service cleanup",
                    verificationEnvironment,
                    backupRestoreCleanupCommand());
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
}
