import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

final class CalendarToolVerification extends CalendarToolProcessRunner {
    private static final String MAVEN_WRAPPER_COMMAND = "mvnw";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String BROWSER_ENVIRONMENT_VARIABLE = "BROWSER";
    private static final String PORT_ENVIRONMENT_VARIABLE = "PORT";
    private static final String DEFAULT_APPLICATION_PORT = "9080";
    private static final String BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME =
            "postgres-bootstrap-verification";
    private static final String BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME =
            "web-bootstrap-verification";
    private static final String BOOTSTRAP_VERIFICATION_PROFILE = "bootstrap-verification";
    private static final String BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_APPLICATION_PORT";
    private static final String BOOTSTRAP_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_HTTPS_PORT";
    private static final String BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_DATABASE_PORT";
    private static final String BOOTSTRAP_VERIFICATION_INVITATION_TOKEN_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_INVITATION_TOKEN";
    private static final String BOOTSTRAP_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_BASE_URL";
    private static final String BOOTSTRAP_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_HEALTH_URL";
    private static final String E2E_VERIFICATION_APPLICATION_SERVICE_NAME = "web-e2e-verification";
    private static final String E2E_VERIFICATION_DATABASE_SERVICE_NAME = "postgres-e2e-verification";
    private static final String E2E_VERIFICATION_PROFILE = "e2e-verification";
    private static final String E2E_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_APPLICATION_PORT";
    private static final String E2E_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_HTTPS_PORT";
    private static final String E2E_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_DATABASE_PORT";
    private static final String E2E_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_BASE_URL";
    private static final String E2E_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE =
            "E2E_VERIFICATION_HEALTH_URL";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT = "9081";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_HTTPS_PORT = "9444";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT = "55432";
    private static final String DEFAULT_E2E_VERIFICATION_APPLICATION_PORT = "9082";
    private static final String DEFAULT_E2E_VERIFICATION_HTTPS_PORT = "9445";
    private static final String DEFAULT_E2E_VERIFICATION_DATABASE_PORT = "55433";
    private static final String E2E_VERIFICATION_DATABASE_NAME = "calendar_e2e_verification";
    private static final String E2E_VERIFICATION_DATABASE_USER = "calendar_e2e_verification";
    private static final String E2E_VERIFICATION_DATABASE_PASSWORD = "calendar_e2e_verification";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_INVITATION_TOKEN =
            "bootstrap-verification-only-token-00000000000000000000000000000000";
    private static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration APPLICATION_READY_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final List<String> APPLICATION_LOG_FAILURE_SIGNATURES = List.of(
            "CWWKS4106E",
            "CWWKS4118E",
            "CWWKS4000E",
            "ViewExpiredException",
            "SRVE0777E",
            "FFDC1015I",
            "SESN0008E");

    private CalendarToolVerification() {}

    static void installPlaywrightBrowsers() throws IOException, InterruptedException {
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

    static void runEndToEndTests() throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        buildDockerImage();
        runSharedEndToEndSelections();
        verifyBootstrapRegistrationConcurrency(false, false);
    }

    static void runSharedEndToEndTests() throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        runSharedEndToEndSelections();
    }

    static void runCrossBrowserSmokeEndToEndTests() throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        String browserName = configuredBrowserName();
        verifySharedCalendarEndToEnd(
                "CrossBrowserSmokeEndToEndIT",
                "e2e-cross-browser-smoke-" + browserName);
    }

    static void runIsolatedEndToEndTest(String selectedTest, String diagnosticDirectoryName)
            throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        verifySharedCalendarEndToEnd(selectedTest, diagnosticDirectoryName);
    }

    private static void runSharedEndToEndSelections() throws IOException, InterruptedException {
        for (EndToEndSelection selection : sharedEndToEndSelections()) {
            verifySharedCalendarEndToEnd(selection.selectedTest(), selection.diagnosticDirectoryName());
        }
    }

    static List<EndToEndSelection> sharedEndToEndSelections() {
        return List.of(
                new EndToEndSelection(null, "e2e-shared"),
                new EndToEndSelection("CalendarLinkRequestThrottleIT", "e2e-calendar-link-throttle"));
    }

    record EndToEndSelection(String selectedTest, String diagnosticDirectoryName) {}

    private static void verifySharedCalendarEndToEnd(
            String selectedTest,
            String diagnosticDirectoryName) throws IOException, InterruptedException {
        E2eVerificationEndpoints endpoints = e2eVerificationEndpoints(System.getenv());
        Map<String, String> verificationEnvironment = new HashMap<>();
        verificationEnvironment.put(
                E2E_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                endpoints.healthControlPort());
        verificationEnvironment.put(E2E_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE, endpoints.httpsPort());
        verificationEnvironment.put(E2E_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE, endpoints.databasePort());
        verificationEnvironment.put(
                E2E_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                endpoints.applicationBaseUri().toString());
        verificationEnvironment.put(
                E2E_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE,
                endpoints.healthControlUri().toString());
        verificationEnvironment.put(
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                endpoints.applicationBaseUri().toString());
        verificationEnvironment.put(CalendarToolPostgresql.POSTGRESQL_HOST_ENVIRONMENT_VARIABLE, "localhost");
        verificationEnvironment.put(
                CalendarToolPostgresql.POSTGRESQL_PORT_ENVIRONMENT_VARIABLE,
                endpoints.databasePort());
        verificationEnvironment.put(
                CalendarToolPostgresql.POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE,
                E2E_VERIFICATION_DATABASE_NAME);
        verificationEnvironment.put(
                CalendarToolPostgresql.POSTGRESQL_USER_ENVIRONMENT_VARIABLE,
                E2E_VERIFICATION_DATABASE_USER);
        verificationEnvironment.put(
                CalendarToolPostgresql.POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE,
                E2E_VERIFICATION_DATABASE_PASSWORD);
        verificationEnvironment.put(BROWSER_ENVIRONMENT_VARIABLE, configuredBrowserName());

        runComposeVerification(new ComposeVerification(
                diagnosticDirectoryName,
                "End-to-end verification",
                "Playwright end-to-end tests",
                E2E_VERIFICATION_PROFILE,
                E2E_VERIFICATION_APPLICATION_SERVICE_NAME,
                E2E_VERIFICATION_DATABASE_SERVICE_NAME,
                endpoints.healthControlUri(),
                verificationEnvironment,
                E2E_VERIFICATION_DATABASE_USER,
                E2E_VERIFICATION_DATABASE_NAME,
                endToEndMavenCommand(selectedTest)));
    }

    static E2eVerificationEndpoints e2eVerificationEndpoints(Map<String, String> environment) {
        String healthControlPort = environmentPortValue(
                environment,
                E2E_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_E2E_VERIFICATION_APPLICATION_PORT);
        String httpsPort = environmentPortValue(
                environment,
                E2E_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_E2E_VERIFICATION_HTTPS_PORT);
        String databasePort = environmentPortValue(
                environment,
                E2E_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_E2E_VERIFICATION_DATABASE_PORT);
        if (healthControlPort.equals(httpsPort)
                || healthControlPort.equals(databasePort)
                || httpsPort.equals(databasePort)) {
            throw new IllegalArgumentException(
                    "E2E verification HTTP, HTTPS, and PostgreSQL ports must be distinct.");
        }

        return new E2eVerificationEndpoints(
                healthControlPort,
                httpsPort,
                databasePort,
                URI.create("https://localhost:" + httpsPort),
                URI.create("http://localhost:" + healthControlPort + "/health"));
    }

    record E2eVerificationEndpoints(
            String healthControlPort,
            String httpsPort,
            String databasePort,
            URI applicationBaseUri,
            URI healthControlUri) {}

    static String[] endToEndMavenCommand(String selectedTest) {
        List<String> command = new ArrayList<>(List.of(MAVEN_WRAPPER_COMMAND, "-Pe2e"));
        if (selectedTest != null) {
            command.add("-Dit.test=" + selectedTest);
        }
        command.addAll(List.of("test-compile", "failsafe:integration-test", "failsafe:verify"));
        return command.toArray(String[]::new);
    }

    static void verifyBootstrapRegistrationConcurrency(boolean installBrowser, boolean buildImage)
            throws IOException, InterruptedException {
        if (installBrowser) {
            installPlaywrightBrowsers();
        }
        if (buildImage) {
            buildDockerImage();
        }

        BootstrapVerificationEndpoints endpoints =
                bootstrapVerificationEndpoints(System.getenv());
        Map<String, String> verificationEnvironment = new HashMap<>();
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                endpoints.healthControlPort());
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE,
                endpoints.httpsPort());
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                endpoints.databasePort());
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_INVITATION_TOKEN_ENVIRONMENT_VARIABLE,
                environmentValueOrDefault(
                        BOOTSTRAP_VERIFICATION_INVITATION_TOKEN_ENVIRONMENT_VARIABLE,
                        DEFAULT_BOOTSTRAP_VERIFICATION_INVITATION_TOKEN));
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                endpoints.applicationBaseUri().toString());
        verificationEnvironment.put(
                BOOTSTRAP_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE,
                endpoints.healthControlUri().toString());
        verificationEnvironment.put(
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                endpoints.applicationBaseUri().toString());
        verificationEnvironment.put(BROWSER_ENVIRONMENT_VARIABLE, configuredBrowserName());

        runComposeVerification(new ComposeVerification(
                "bootstrap-registration",
                "Bootstrap verification",
                "Bootstrap registration concurrency verification",
                BOOTSTRAP_VERIFICATION_PROFILE,
                BOOTSTRAP_VERIFICATION_APPLICATION_SERVICE_NAME,
                BOOTSTRAP_VERIFICATION_DATABASE_SERVICE_NAME,
                endpoints.healthControlUri(),
                verificationEnvironment,
                "calendar_bootstrap_verification",
                "calendar_bootstrap_verification",
                new String[] {
                    MAVEN_WRAPPER_COMMAND,
                    "-Pbootstrap-concurrency-e2e",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify"
                }));
    }

    static BootstrapVerificationEndpoints bootstrapVerificationEndpoints(
            Map<String, String> environment) {
        String healthControlPort = environmentPortValue(
                environment,
                BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT);
        String httpsPort = environmentPortValue(
                environment,
                BOOTSTRAP_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_HTTPS_PORT);
        String databasePort = environmentPortValue(
                environment,
                BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT);
        if (healthControlPort.equals(httpsPort)
                || healthControlPort.equals(databasePort)
                || httpsPort.equals(databasePort)) {
            throw new IllegalArgumentException(
                    "Bootstrap verification HTTP, HTTPS, and PostgreSQL ports must be distinct.");
        }

        return new BootstrapVerificationEndpoints(
                healthControlPort,
                httpsPort,
                databasePort,
                URI.create("https://localhost:" + httpsPort),
                URI.create("http://localhost:" + healthControlPort + "/health"));
    }

    record BootstrapVerificationEndpoints(
            String healthControlPort,
            String httpsPort,
            String databasePort,
            URI applicationBaseUri,
            URI healthControlUri) {}

    private static void runComposeVerification(ComposeVerification verification)
            throws IOException, InterruptedException {
        clearVerificationDiagnostics(verification.diagnosticDirectoryName());
        boolean startupAttempted = false;
        Throwable primaryFailure = null;
        try {
            startupAttempted = true;
            runCommandWithEnvironment(
                    verification.displayName() + " application startup",
                    verification.environment(),
                    composeUpCommand(
                            verification.profile(),
                            verification.databaseServiceName(),
                            verification.applicationServiceName()));
            waitForApplication(verification.healthUri());
            CalendarToolPostgresql.checkComposeDatabaseSchema(
                    verification.databaseServiceName(),
                    verification.databaseUser(),
                    verification.databaseName());
            runCommandWithEnvironment(
                    verification.testDescription(),
                    verification.environment(),
                    verification.verificationCommand());
            verifyApplicationRuntimeLogs(verification);
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            primaryFailure = exception;
            persistVerificationDiagnostics(exception, verification);
            throw exception;
        } finally {
            if (startupAttempted) {
                cleanUpComposeServices(
                        verification.displayName() + " service cleanup",
                        primaryFailure,
                        verification.environment(),
                        verification.profile(),
                        verification.applicationServiceName(),
                        verification.databaseServiceName());
            }
        }
    }

    record ComposeVerification(
            String diagnosticDirectoryName,
            String displayName,
            String testDescription,
            String profile,
            String applicationServiceName,
            String databaseServiceName,
            URI healthUri,
            Map<String, String> environment,
            String databaseUser,
            String databaseName,
            String[] verificationCommand) {
        ComposeVerification {
            environment = Map.copyOf(environment);
            verificationCommand = verificationCommand.clone();
        }

        @Override
        public String[] verificationCommand() {
            return verificationCommand.clone();
        }
    }

    private static void verifyApplicationRuntimeLogs(ComposeVerification verification)
            throws IOException, InterruptedException {
        CapturedCommandOutput applicationLogOutput = runCommandAndCaptureCombinedOutput(
                verification.environment(),
                composeLogsCommand(
                        verification.profile(),
                        verification.applicationServiceName()));
        if (applicationLogOutput.exitCode() != 0) {
            throw new IllegalStateException(
                    "Application log verification failed with exit code "
                            + applicationLogOutput.exitCode() + ".");
        }
        validateApplicationRuntimeLogs(applicationLogOutput.output());
    }

    static void validateApplicationRuntimeLogs(String applicationLogs) {
        String nonNullApplicationLogs = applicationLogs == null ? "" : applicationLogs;
        List<String> detectedFailureSignatures =
                APPLICATION_LOG_FAILURE_SIGNATURES.stream()
                        .filter(nonNullApplicationLogs::contains)
                        .toList();
        if (!detectedFailureSignatures.isEmpty()) {
            throw new IllegalStateException(
                    "Application logs contain failure signatures: "
                            + String.join(", ", detectedFailureSignatures) + ".");
        }
    }

    static void waitForApplication() throws InterruptedException {
        waitForApplication(applicationHealthUri());
    }

    private static void waitForApplication(URI healthUri) throws InterruptedException {
        waitForApplication(
                healthUri,
                APPLICATION_READY_TIMEOUT,
                APPLICATION_READY_POLL_INTERVAL,
                CalendarToolVerification::checkApplicationHealth,
                System::nanoTime,
                Thread::sleep);
    }

    static void waitForApplication(
            URI healthUri,
            Duration timeout,
            Duration pollInterval,
            HealthProbe healthProbe,
            LongSupplier nanoTime,
            Sleeper sleeper) throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Application readiness timeout must be positive.");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("Application readiness poll interval must be positive.");
        }

        long deadlineNanos = nanoTime.getAsLong() + timeout.toNanos();
        Exception lastHealthCheckFailure = null;

        while (nanoTime.getAsLong() < deadlineNanos) {
            try {
                healthProbe.check(healthUri);
                return;
            } catch (IOException | IllegalStateException exception) {
                lastHealthCheckFailure = exception;
            }

            sleeper.sleep(pollInterval.toMillis());
        }

        throw new IllegalStateException(
                "Application did not become healthy within " + timeout.toSeconds() + " seconds.",
                lastHealthCheckFailure);
    }

    @FunctionalInterface
    interface HealthProbe {
        void check(URI healthUri) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    static void verifyLocal() throws IOException, InterruptedException {
        checkApplicationHealth();
        CalendarToolPostgresql.checkDatabaseSchema();
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
                    "Health check failed for " + healthUri
                            + ". Start the app with 'mise run dev' before running this check.",
                    exception);
        }

        validateHealthResponse(healthUri, response.statusCode(), response.body());
        System.out.println("Health check returned HTTP 200 with body 'ok' from " + healthUri + ".");
    }

    static void validateHealthResponse(URI healthUri, int statusCode, String responseBody) {
        String normalizedResponseBody = responseBody == null ? "" : responseBody.trim();
        if (statusCode != 200 || !normalizedResponseBody.equals("ok")) {
            throw new IllegalStateException(
                    "Health check expected HTTP 200 with body 'ok', but got HTTP "
                            + statusCode + " with body '" + normalizedResponseBody + "' from " + healthUri + ".");
        }
    }

    private static URI applicationHealthUri() {
        return applicationHealthUri(System.getenv());
    }

    static URI applicationHealthUri(Map<String, String> environment) {
        String defaultApplicationBaseUrl = "http://localhost:"
                + environmentPortValue(environment, PORT_ENVIRONMENT_VARIABLE, DEFAULT_APPLICATION_PORT);
        String applicationBaseUrl = environmentValueOrDefault(
                environment,
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                defaultApplicationBaseUrl);
        URI baseUri;
        try {
            baseUri = URI.create(removeTrailingSlashes(applicationBaseUrl));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("APP_BASE_URL must be a valid HTTP or HTTPS URL.", exception);
        }
        if (!(baseUri.getScheme() != null
                        && (baseUri.getScheme().equalsIgnoreCase("http")
                                || baseUri.getScheme().equalsIgnoreCase("https")))
                || baseUri.getHost() == null
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "APP_BASE_URL must be an HTTP or HTTPS URL without credentials, a query, or a fragment.");
        }
        return URI.create(baseUri + "/health");
    }

    static String configuredBrowserName() {
        return configuredBrowserName(System.getenv());
    }

    static String configuredBrowserName(Map<String, String> environment) {
        String browserName = environmentValueOrDefault(environment, BROWSER_ENVIRONMENT_VARIABLE, "chromium")
                .toLowerCase(Locale.ROOT);
        return switch (browserName) {
            case "chromium", "firefox", "webkit" -> browserName;
            default -> throw new IllegalArgumentException(
                    "Unsupported Playwright browser '" + browserName + "'. Use chromium, firefox, or webkit.");
        };
    }

    static String environmentValueOrDefault(String variableName, String defaultValue) {
        return environmentValueOrDefault(System.getenv(), variableName, defaultValue);
    }

    static String environmentValueOrDefault(
            Map<String, String> environment,
            String variableName,
            String defaultValue) {
        String configuredValue = environment.get(variableName);
        if (configuredValue == null || configuredValue.isBlank()) {
            return defaultValue;
        }
        return configuredValue.trim();
    }

    static String environmentPortValue(String variableName, String defaultValue) {
        return environmentPortValue(System.getenv(), variableName, defaultValue);
    }

    static String environmentPortValue(
            Map<String, String> environment,
            String variableName,
            String defaultValue) {
        String configuredValue = environmentValueOrDefault(environment, variableName, defaultValue);
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

    private static String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }

    private static void clearVerificationDiagnostics(String diagnosticDirectoryName) throws IOException {
        Path diagnosticDirectory = verificationDiagnosticDirectory(diagnosticDirectoryName);
        Files.createDirectories(diagnosticDirectory);
        Files.deleteIfExists(diagnosticDirectory.resolve("failure.txt"));
        Files.deleteIfExists(diagnosticDirectory.resolve("context.txt"));
        Files.deleteIfExists(diagnosticDirectory.resolve("compose.log"));
    }

    private static void persistVerificationDiagnostics(
            Throwable primaryFailure,
            ComposeVerification verification) {
        Path diagnosticDirectory = verificationDiagnosticDirectory(verification.diagnosticDirectoryName());
        try {
            Files.createDirectories(diagnosticDirectory);
            StringWriter failureText = new StringWriter();
            primaryFailure.printStackTrace(new PrintWriter(failureText));
            Files.writeString(
                    diagnosticDirectory.resolve("failure.txt"),
                    failureText.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(
                    diagnosticDirectory.resolve("context.txt"),
                    verificationDiagnosticContext(
                            verification.profile(),
                            verification.environment(),
                            verification.healthUri()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            CapturedCommandOutput composeLogOutput = runCommandAndCaptureCombinedOutput(
                    verification.environment(),
                    composeLogsCommand(
                            verification.profile(),
                            verification.applicationServiceName(),
                            verification.databaseServiceName()));
            Files.writeString(
                    diagnosticDirectory.resolve("compose.log"),
                    composeLogOutput.output(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            System.err.print(composeLogOutput.output());
            System.err.println("Verification diagnostics were written to " + diagnosticDirectory + ".");
            if (composeLogOutput.exitCode() != 0) {
                throw new IllegalStateException(
                        "Diagnostic logs failed with exit code " + composeLogOutput.exitCode() + ".");
            }
        } catch (IOException | InterruptedException | RuntimeException diagnosticFailure) {
            if (diagnosticFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            primaryFailure.addSuppressed(diagnosticFailure);
        }
    }

    private static Path verificationDiagnosticDirectory(String diagnosticDirectoryName) {
        return PROJECT_DIRECTORY.resolve(".build/verification").resolve(diagnosticDirectoryName);
    }

    static String verificationDiagnosticContext(
            String profile,
            Map<String, String> environment,
            URI healthControlUri) {
        return "Compose profile: " + profile + System.lineSeparator()
                + "Browser: " + environment.getOrDefault(BROWSER_ENVIRONMENT_VARIABLE, "not set")
                + System.lineSeparator()
                + "Application base URL: "
                + environment.getOrDefault(APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE, "not set")
                + System.lineSeparator()
                + "Health control URL: " + healthControlUri + System.lineSeparator();
    }

    static String[] composeUpCommand(String profile, String... serviceNames) {
        List<String> command = composeProfileCommand(profile, "up", "-d", "--force-recreate", "--no-build");
        command.addAll(List.of(serviceNames));
        return command.toArray(String[]::new);
    }

    static String[] composeLogsCommand(String profile, String... serviceNames) {
        List<String> command = composeProfileCommand(profile, "logs", "--no-color");
        command.addAll(List.of(serviceNames));
        return command.toArray(String[]::new);
    }

    static String[] composeCleanupCommand(String profile, String... serviceNames) {
        List<String> command = composeProfileCommand(profile, "rm", "--force", "--stop");
        command.addAll(List.of(serviceNames));
        return command.toArray(String[]::new);
    }

    private static List<String> composeProfileCommand(String profile, String operation, String... options) {
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--profile",
                profile,
                operation));
        command.addAll(List.of(options));
        return command;
    }

    private static void cleanUpComposeServices(
            String description,
            Throwable primaryFailure,
            Map<String, String> environment,
            String profile,
            String... serviceNames) throws IOException, InterruptedException {
        try {
            runCommandWithEnvironment(description, environment, composeCleanupCommand(profile, serviceNames));
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
