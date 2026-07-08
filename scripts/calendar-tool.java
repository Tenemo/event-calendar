import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class CalendarTool {
    private static final Path PROJECT_DIRECTORY = Path.of("").toAbsolutePath().normalize();
    private static final Path LIBERTY_SHARED_POSTGRESQL_DIRECTORY =
            PROJECT_DIRECTORY.resolve("target/liberty/wlp/usr/shared/resources/postgresql");
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String PORT_ENVIRONMENT_VARIABLE = "PORT";
    private static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    private static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    private static final String DEFAULT_APPLICATION_PORT = "9080";
    private static final String DEFAULT_DATABASE_NAME = "calendar";
    private static final String DEFAULT_DATABASE_USER = "calendar";
    private static final String DATABASE_SERVICE_NAME = "postgres";
    private static final Duration DATABASE_READY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DATABASE_READY_POLL_INTERVAL = Duration.ofSeconds(2);

    public static void main(String[] arguments) throws Exception {
        requireProjectDirectory();

        if (arguments.length != 1) {
            printUsage();
            System.exit(1);
        }

        switch (arguments[0]) {
            case "check-toolchain" -> checkToolchain();
            case "prepare-liberty-dev" -> prepareLibertyDev();
            case "setup" -> setup();
            case "db" -> startDatabase();
            case "dev" -> startDevelopmentServer();
            case "package" -> packageApplication();
            case "verify-local" -> verifyLocal();
            case "verify-running-app" -> verifyRunningApplication();
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

    private static void verifyLocal() throws IOException, InterruptedException {
        packageApplication();
        startDatabase();
        verifyRunningApplication();
    }

    private static void verifyRunningApplication() throws IOException, InterruptedException {
        checkApplicationHealth();
        checkDatabaseConnection();
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

    private static void waitForDatabase() throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + DATABASE_READY_TIMEOUT.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            int readinessExitCode = runCommandForExitCode(
                    false,
                    "docker",
                    "compose",
                    "exec",
                    "-T",
                    DATABASE_SERVICE_NAME,
                    "pg_isready",
                    "-U",
                    databaseUser(),
                    "-d",
                    databaseName());

            if (readinessExitCode == 0) {
                System.out.println("PostgreSQL is ready.");
                return;
            }

            Thread.sleep(DATABASE_READY_POLL_INTERVAL.toMillis());
        }

        throw new IllegalStateException(
                "PostgreSQL did not become ready within " + DATABASE_READY_TIMEOUT.toSeconds() + " seconds.");
    }

    private static void checkApplicationHealth() throws IOException, InterruptedException {
        URI healthUri = applicationHealthUri();
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

    private static int runCommandForExitCode(boolean inheritOutput, String... command)
            throws IOException, InterruptedException {
        List<String> commandLine = new ArrayList<>(Arrays.asList(command));
        commandLine.set(0, platformExecutableName(commandLine.get(0)));
        ProcessBuilder processBuilder = new ProcessBuilder(commandLine)
                .directory(PROJECT_DIRECTORY.toFile());

        if (inheritOutput) {
            processBuilder.inheritIO();
        } else {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new IOException("Required command '" + commandLine.getFirst() + "' was not found on PATH.", exception);
        }

        return process.waitFor();
    }

    private static void printUsage() {
        String executableName = isWindows() ? "java scripts\\calendar-tool.java" : "java scripts/calendar-tool.java";
        System.err.println("Usage: " + executableName + " <command>");
        System.err.println(
                "Commands: check-toolchain, prepare-liberty-dev, setup, db, dev, package, verify-local, verify-running-app");
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
