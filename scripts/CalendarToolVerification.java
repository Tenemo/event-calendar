import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CalendarToolVerification extends CalendarToolProcessRunner {
    private static final String MAVEN_WRAPPER_COMMAND = "mvnw";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String BROWSER_ENVIRONMENT_VARIABLE = "BROWSER";
    private static final String PORT_ENVIRONMENT_VARIABLE = "PORT";
    private static final String HTTPS_PORT_ENVIRONMENT_VARIABLE = "HTTPS_PORT";
    private static final String EXPECTED_DEPLOYMENT_REVISION_ENVIRONMENT_VARIABLE = "EXPECTED_DEPLOYMENT_REVISION";
    private static final String DEPLOYMENT_REVISION_HEADER = "X-Deployment-Revision";
    private static final String DEFAULT_APPLICATION_PORT = "9080";
    private static final String DEFAULT_APPLICATION_HTTPS_PORT = "9443";
    private static final Path LOCAL_IMAGE_BUILD_STATE_PATH =
            PROJECT_DIRECTORY.resolve(".build/image-state/shared-calendar-local.txt");
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
    private static final String END_TO_END_VERIFICATION_APPLICATION_SERVICE_NAME = "web-end-to-end-verification";
    private static final String END_TO_END_VERIFICATION_DATABASE_SERVICE_NAME = "postgres-end-to-end-verification";
    private static final String END_TO_END_VERIFICATION_PROFILE = "end-to-end-verification";
    private static final String END_TO_END_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE =
            "END_TO_END_VERIFICATION_APPLICATION_PORT";
    private static final String END_TO_END_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE =
            "END_TO_END_VERIFICATION_HTTPS_PORT";
    private static final String END_TO_END_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE =
            "END_TO_END_VERIFICATION_DATABASE_PORT";
    private static final String END_TO_END_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE =
            "END_TO_END_VERIFICATION_BASE_URL";
    private static final String END_TO_END_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE =
            "END_TO_END_VERIFICATION_HEALTH_URL";
    private static final String END_TO_END_MANAGED_RECOVERY_SCENARIOS_ENVIRONMENT_VARIABLE =
            "END_TO_END_MANAGED_RECOVERY_SCENARIOS";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT = "9081";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_HTTPS_PORT = "9444";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT = "55432";
    private static final String DEFAULT_END_TO_END_VERIFICATION_APPLICATION_PORT = "9082";
    private static final String DEFAULT_END_TO_END_VERIFICATION_HTTPS_PORT = "9445";
    private static final String DEFAULT_END_TO_END_VERIFICATION_DATABASE_PORT = "55433";
    private static final String END_TO_END_VERIFICATION_DATABASE_NAME = "calendar_end_to_end_verification";
    private static final String END_TO_END_VERIFICATION_DATABASE_USER = "calendar_end_to_end_verification";
    private static final String END_TO_END_VERIFICATION_DATABASE_PASSWORD = "calendar_end_to_end_verification";
    private static final String DEFAULT_BOOTSTRAP_VERIFICATION_INVITATION_TOKEN =
            "bootstrap-verification-only-token-00000000000000000000000000000000";
    private static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration APPLICATION_READY_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final List<String> APPLICATION_LOG_FAILURE_SIGNATURES = List.of(
            "SEVERE:",
            "\"loglevel\":\"SEVERE\"",
            "CWWKS4106E",
            "CWWKS4118E",
            "CWWKS4000E",
            "ViewExpiredException",
            "SRVE0777E",
            "FFDC1015I",
            "SESN0008E");
    private static final Pattern COMPOSE_CONFIGURATION_HASH_LABEL_PATTERN = Pattern.compile(
            "\"com\\.docker\\.compose\\.config-hash\"\\s*:\\s*"
                    + "\"(?<configurationHash>[0-9a-f]{64})\"");

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
                "end-to-end-cross-browser-smoke-" + browserName);
    }

    static void verifyPreviewDeployment() throws IOException, InterruptedException {
        installPlaywrightBrowsers();
        runCommand("Preview deployment authentication", previewDeploymentMavenCommand());
    }

    static String[] previewDeploymentMavenCommand() {
        return new String[] {
            MAVEN_WRAPPER_COMMAND,
            "-Ppreview-deployment-end-to-end",
            "test-compile",
            "failsafe:integration-test",
            "failsafe:verify"
        };
    }

    private static void runSharedEndToEndSelections() throws IOException, InterruptedException {
        verifySharedCalendarEndToEnd(null, "end-to-end-shared");
    }

    private static void verifySharedCalendarEndToEnd(
            String selectedTest,
            String diagnosticDirectoryName) throws IOException, InterruptedException {
        VerificationEndpoints endpoints = endToEndVerificationEndpoints(System.getenv());
        Map<String, String> verificationEnvironment = new HashMap<>();
        verificationEnvironment.put(
                END_TO_END_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                endpoints.healthControlPort());
        verificationEnvironment.put(END_TO_END_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE, endpoints.httpsPort());
        verificationEnvironment.put(END_TO_END_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE, endpoints.databasePort());
        verificationEnvironment.put(
                END_TO_END_VERIFICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                endpoints.applicationBaseUri().toString());
        verificationEnvironment.put(
                END_TO_END_VERIFICATION_HEALTH_URL_ENVIRONMENT_VARIABLE,
                endpoints.healthControlUri().toString());
        verificationEnvironment.put(
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                endpoints.applicationBaseUri().toString());
        verificationEnvironment.put(CalendarToolPostgreSql.POSTGRESQL_HOST_ENVIRONMENT_VARIABLE, "localhost");
        verificationEnvironment.put(
                CalendarToolPostgreSql.POSTGRESQL_PORT_ENVIRONMENT_VARIABLE,
                endpoints.databasePort());
        verificationEnvironment.put(
                CalendarToolPostgreSql.POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE,
                END_TO_END_VERIFICATION_DATABASE_NAME);
        verificationEnvironment.put(
                CalendarToolPostgreSql.POSTGRESQL_USER_ENVIRONMENT_VARIABLE,
                END_TO_END_VERIFICATION_DATABASE_USER);
        verificationEnvironment.put(
                CalendarToolPostgreSql.POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE,
                END_TO_END_VERIFICATION_DATABASE_PASSWORD);
        verificationEnvironment.put(BROWSER_ENVIRONMENT_VARIABLE, configuredBrowserName());
        verificationEnvironment.put(END_TO_END_MANAGED_RECOVERY_SCENARIOS_ENVIRONMENT_VARIABLE, "true");

        runComposeVerification(new ComposeVerification(
                diagnosticDirectoryName,
                "End-to-end verification",
                "Playwright end-to-end tests",
                END_TO_END_VERIFICATION_PROFILE,
                END_TO_END_VERIFICATION_APPLICATION_SERVICE_NAME,
                END_TO_END_VERIFICATION_DATABASE_SERVICE_NAME,
                endpoints.healthControlUri(),
                verificationEnvironment,
                END_TO_END_VERIFICATION_DATABASE_USER,
                END_TO_END_VERIFICATION_DATABASE_NAME,
                endToEndMavenCommand(selectedTest)));
    }

    static VerificationEndpoints endToEndVerificationEndpoints(
            Map<String, String> environment) {
        return verificationEndpoints(
                environment,
                END_TO_END_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_END_TO_END_VERIFICATION_APPLICATION_PORT,
                END_TO_END_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_END_TO_END_VERIFICATION_HTTPS_PORT,
                END_TO_END_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_END_TO_END_VERIFICATION_DATABASE_PORT,
                "end-to-end verification");
    }

    static String[] endToEndMavenCommand(String selectedTest) {
        List<String> command = new ArrayList<>(List.of(MAVEN_WRAPPER_COMMAND, "-Pend-to-end"));
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

        VerificationEndpoints endpoints =
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
                    "-Pbootstrap-concurrency-end-to-end",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify"
                }));
    }

    static VerificationEndpoints bootstrapVerificationEndpoints(
            Map<String, String> environment) {
        return verificationEndpoints(
                environment,
                BOOTSTRAP_VERIFICATION_APPLICATION_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_APPLICATION_PORT,
                BOOTSTRAP_VERIFICATION_HTTPS_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_HTTPS_PORT,
                BOOTSTRAP_VERIFICATION_DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_BOOTSTRAP_VERIFICATION_DATABASE_PORT,
                "Bootstrap verification");
    }

    private static VerificationEndpoints verificationEndpoints(
            Map<String, String> environment,
            String healthControlPortEnvironmentVariable,
            String defaultHealthControlPort,
            String httpsPortEnvironmentVariable,
            String defaultHttpsPort,
            String databasePortEnvironmentVariable,
            String defaultDatabasePort,
            String verificationName) {
        String healthControlPort = environmentPortValue(
                environment,
                healthControlPortEnvironmentVariable,
                defaultHealthControlPort);
        String httpsPort = environmentPortValue(
                environment,
                httpsPortEnvironmentVariable,
                defaultHttpsPort);
        String databasePort = environmentPortValue(
                environment,
                databasePortEnvironmentVariable,
                defaultDatabasePort);
        if (healthControlPort.equals(httpsPort)
                || healthControlPort.equals(databasePort)
                || httpsPort.equals(databasePort)) {
            throw new IllegalArgumentException(
                    verificationName
                            + " HTTP, HTTPS, and PostgreSQL ports must be distinct.");
        }

        return new VerificationEndpoints(
                healthControlPort,
                httpsPort,
                databasePort,
                URI.create("https://localhost:" + httpsPort),
                URI.create("http://localhost:" + healthControlPort + "/health"));
    }

    record VerificationEndpoints(
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
            CalendarToolPostgreSql.checkComposeDatabaseSchema(
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

    static void waitForApplication(URI healthUri) throws InterruptedException {
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
        URI healthUri = applicationHealthUri();
        ApplicationHealthResponse healthResponse = requestApplicationHealth(healthUri);
        validateExpectedDeploymentRevision(
                System.getenv().get(EXPECTED_DEPLOYMENT_REVISION_ENVIRONMENT_VARIABLE),
                healthResponse.deploymentRevision());

        boolean composeApplication = runningComposeApplicationUsesPort(healthUri.getPort());
        if (composeApplication) {
            CalendarToolPostgreSql.verifyComposeApplicationDatabaseConfiguration();
            verifyRunningComposeApplicationRuntimeConfigurationIsCurrent();
            if (System.getenv().get(EXPECTED_DEPLOYMENT_REVISION_ENVIRONMENT_VARIABLE) == null
                    || System.getenv().get(EXPECTED_DEPLOYMENT_REVISION_ENVIRONMENT_VARIABLE).isBlank()) {
                verifyRunningComposeImageIsCurrent();
            }
        } else {
            if (System.getenv().get(EXPECTED_DEPLOYMENT_REVISION_ENVIRONMENT_VARIABLE) != null
                    && !System.getenv().get(EXPECTED_DEPLOYMENT_REVISION_ENVIRONMENT_VARIABLE).isBlank()) {
                throw new IllegalStateException(
                        "EXPECTED_DEPLOYMENT_REVISION requires the Compose application on the configured local HTTP port.");
            }
            verifyDevelopmentBuildIsCurrent(PROJECT_DIRECTORY);
        }

        verifyRunningComposeDatabaseServiceIsCurrent();
        CalendarToolPostgreSql.checkDatabaseSchema();
        CalendarToolPostgreSql.verifyApplicationConnectionToComposeDatabase(
                composeApplication
                        ? composeDatabaseApplicationName(System.getenv())
                        : developmentDatabaseApplicationName(System.getenv()));
        System.out.println("Local verification matched the current application build and intended Compose database.");
    }

    private static void checkApplicationHealth(URI healthUri) throws IOException, InterruptedException {
        requestApplicationHealth(healthUri);
    }

    private static ApplicationHealthResponse requestApplicationHealth(URI healthUri)
            throws IOException, InterruptedException {
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
        return new ApplicationHealthResponse(
                response.statusCode(),
                response.body(),
                response.headers().firstValue(DEPLOYMENT_REVISION_HEADER).orElse(null));
    }

    record ApplicationHealthResponse(int statusCode, String body, String deploymentRevision) {}

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
        String applicationPort = environmentPortValue(
                environment,
                PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_APPLICATION_PORT);
        return URI.create("http://localhost:" + applicationPort + "/health");
    }

    static String composeDatabaseApplicationName(Map<String, String> environment) {
        return "shared-calendar-compose-web-" + environmentPortValue(
                environment,
                PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_APPLICATION_PORT);
    }

    static String developmentDatabaseApplicationName(Map<String, String> environment) {
        return "shared-calendar-development-" + environmentPortValue(
                environment,
                PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_APPLICATION_PORT);
    }

    static void validateExpectedDeploymentRevision(String expectedRevision, String actualRevision) {
        if (expectedRevision == null || expectedRevision.isBlank()) {
            return;
        }
        String normalizedExpectedRevision = expectedRevision.trim().toLowerCase(Locale.ROOT);
        if (!normalizedExpectedRevision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                    "EXPECTED_DEPLOYMENT_REVISION must be a full 40-character hexadecimal revision.");
        }
        String normalizedActualRevision = actualRevision == null
                ? ""
                : actualRevision.trim().toLowerCase(Locale.ROOT);
        if (!normalizedExpectedRevision.equals(normalizedActualRevision)) {
            throw new IllegalStateException(
                    "The application health response did not identify the expected deployment revision. "
                            + "Rebuild and restart the application before verification.");
        }
    }

    static boolean composePortMappingIncludes(String composePortOutput, int expectedPort) {
        if (composePortOutput == null || composePortOutput.isBlank()) {
            return false;
        }
        for (String mapping : composePortOutput.lines().toList()) {
            String trimmedMapping = mapping.trim();
            int finalColonIndex = trimmedMapping.lastIndexOf(':');
            if (finalColonIndex < 0 || finalColonIndex == trimmedMapping.length() - 1) {
                throw new IllegalArgumentException("Docker Compose returned a malformed application port mapping.");
            }
            try {
                if (Integer.parseInt(trimmedMapping.substring(finalColonIndex + 1)) == expectedPort) {
                    return true;
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "Docker Compose returned a non-numeric application port mapping.", exception);
            }
        }
        return false;
    }

    private static boolean runningComposeApplicationUsesPort(int expectedPort)
            throws IOException, InterruptedException {
        String runningApplicationService = runCommandAndCapture(
                "Compose application state verification",
                "docker",
                "compose",
                "--profile",
                "application",
                "ps",
                "--services",
                "--status",
                "running",
                "web").trim();
        if (!runningApplicationService.lines().anyMatch("web"::equals)) {
            return false;
        }
        String portMapping = runCommandAndCapture(
                "Compose application port verification",
                "docker",
                "compose",
                "port",
                "web",
                "9080");
        validateComposePortMappingsAreLoopbackOnly(portMapping);
        if (!composePortMappingIncludes(portMapping, expectedPort)) {
            throw new IllegalStateException(
                    "The running Compose application does not own the configured local health port. "
                            + "Stop stale or additional application instances before verification.");
        }
        return true;
    }

    private static void verifyRunningComposeImageIsCurrent() throws IOException, InterruptedException {
        String containerIdentifier = runCommandAndCapture(
                "Compose application container lookup",
                "docker",
                "compose",
                "ps",
                "--quiet",
                "web").trim();
        if (containerIdentifier.isEmpty()) {
            throw new IllegalStateException("The running Compose application container could not be identified.");
        }
        String runningImageIdentifier = runCommandAndCapture(
                "Compose application image lookup",
                "docker",
                "inspect",
                "--format={{.Image}}",
                containerIdentifier).trim();
        String currentImageIdentifier = runCommandAndCapture(
                "Local application image lookup",
                "docker",
                "image",
                "inspect",
                "--format={{.Id}}",
                "shared-calendar:local").trim();
        if (containerIdentifier.isEmpty()
                || runningImageIdentifier.isEmpty()
                || !runningImageIdentifier.equals(currentImageIdentifier)) {
            throw new IllegalStateException(
                    "The running Compose application is not using the current shared-calendar:local image.");
        }
        verifyRecordedImageBuildState(PROJECT_DIRECTORY, currentImageIdentifier);
    }

    static void validateComposePortMappingsAreLoopbackOnly(String composePortOutput) {
        if (composePortOutput == null || composePortOutput.isBlank()) {
            throw new IllegalStateException(
                    "The running Compose application did not report its HTTP port binding.");
        }
        for (String mapping : composePortOutput.lines().map(String::trim).filter(line -> !line.isEmpty()).toList()) {
            int finalColonIndex = mapping.lastIndexOf(':');
            if (finalColonIndex <= 0) {
                throw new IllegalArgumentException("Docker Compose returned a malformed application port mapping.");
            }
            String host = mapping.substring(0, finalColonIndex);
            if (!host.equals("127.0.0.1") && !host.equals("[::1]")) {
                throw new IllegalStateException(
                        "A running Compose service exposes a port beyond loopback. "
                                + "Recreate it from the current Compose configuration before verification.");
            }
        }
    }

    static void validateComposeServiceConfigurationHash(
            String serviceName,
            String currentConfigurationHashOutput,
            String runningConfigurationHashOutput) {
        String currentConfigurationHash = currentConfigurationHashOutput == null
                ? ""
                : currentConfigurationHashOutput.trim();
        String expectedPrefix = serviceName + " ";
        if (!currentConfigurationHash.startsWith(expectedPrefix)
                || currentConfigurationHash.lines().count() != 1) {
            throw new IllegalStateException(
                    "Docker Compose did not report the current " + serviceName + " service configuration hash.");
        }
        currentConfigurationHash = currentConfigurationHash.substring(expectedPrefix.length()).trim();
        String runningConfigurationHash = runningConfigurationHashOutput == null
                ? ""
                : runningConfigurationHashOutput.trim();
        if (!currentConfigurationHash.matches("[0-9a-f]{64}")
                || !runningConfigurationHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Docker Compose returned an invalid " + serviceName + " service configuration hash.");
        }
        if (!currentConfigurationHash.equals(runningConfigurationHash)) {
            throw new IllegalStateException(
                    "The running Compose application was created from stale service configuration. "
                            + "Recreate it before verification.");
        }
    }

    static String composeConfigurationHashFromLabelsJson(String labelsJson) {
        if (labelsJson == null) {
            return "";
        }
        Matcher configurationHashMatcher =
                COMPOSE_CONFIGURATION_HASH_LABEL_PATTERN.matcher(labelsJson);
        if (!configurationHashMatcher.find()) {
            return "";
        }
        String configurationHash = configurationHashMatcher.group("configurationHash");
        return configurationHashMatcher.find() ? "" : configurationHash;
    }

    static String[] composeServiceLabelsInspectionCommand(String containerIdentifier) {
        return new String[] {
            "docker",
            "inspect",
            "--format={{json .Config.Labels}}",
            containerIdentifier
        };
    }

    private static void verifyRunningComposeServiceConfigurationIsCurrent(
            String serviceName,
            String containerIdentifier) throws IOException, InterruptedException {
        String currentServiceHash = runCommandAndCapture(
                "Current Compose " + serviceName + " configuration lookup",
                "docker",
                "compose",
                "--profile",
                "application",
                "config",
                "--hash",
                serviceName);
        String runningServiceLabelsJson = runCommandAndCapture(
                "Running Compose " + serviceName + " configuration lookup",
                composeServiceLabelsInspectionCommand(containerIdentifier));
        String runningServiceHash =
                composeConfigurationHashFromLabelsJson(runningServiceLabelsJson);
        validateComposeServiceConfigurationHash(serviceName, currentServiceHash, runningServiceHash);
    }

    private static void verifyRunningComposeApplicationRuntimeConfigurationIsCurrent()
            throws IOException, InterruptedException {
        String applicationContainerIdentifier = runCommandAndCapture(
                "Compose application container lookup",
                "docker",
                "compose",
                "ps",
                "--quiet",
                "web").trim();
        if (applicationContainerIdentifier.isEmpty()) {
            throw new IllegalStateException("The running Compose application container could not be identified.");
        }
        verifyRunningComposeServiceConfigurationIsCurrent("web", applicationContainerIdentifier);

        String httpsPortMapping = runCommandAndCapture(
                "Compose application HTTPS port verification",
                "docker",
                "compose",
                "port",
                "web",
                "9443");
        validateExpectedLoopbackPortMapping(
                httpsPortMapping,
                Integer.parseInt(environmentPortValue(
                        HTTPS_PORT_ENVIRONMENT_VARIABLE,
                        DEFAULT_APPLICATION_HTTPS_PORT)),
                "application HTTPS");
    }

    private static void verifyRunningComposeDatabaseServiceIsCurrent()
            throws IOException, InterruptedException {
        String databasePortMapping = runCommandAndCapture(
                "Compose PostgreSQL port verification",
                "docker",
                "compose",
                "port",
                "postgres",
                "5432");
        validateExpectedLoopbackPortMapping(
                databasePortMapping,
                Integer.parseInt(environmentPortValue(
                        CalendarToolPostgreSql.POSTGRESQL_PORT_ENVIRONMENT_VARIABLE,
                        "5432")),
                "PostgreSQL");

        String databaseContainerIdentifier = runCommandAndCapture(
                "Compose PostgreSQL container lookup",
                "docker",
                "compose",
                "ps",
                "--quiet",
                "postgres").trim();
        if (databaseContainerIdentifier.isEmpty()) {
            throw new IllegalStateException("The running Compose PostgreSQL container could not be identified.");
        }
        verifyRunningComposeServiceConfigurationIsCurrent("postgres", databaseContainerIdentifier);
    }

    static void validateExpectedLoopbackPortMapping(
            String portMapping,
            int expectedPort,
            String bindingName) {
        validateComposePortMappingsAreLoopbackOnly(portMapping);
        if (!composePortMappingIncludes(portMapping, expectedPort)) {
            throw new IllegalStateException(
                    "The running Compose " + bindingName + " binding does not use the configured local port.");
        }
    }

    static void recordCurrentImageBuildState(String inputDigest) throws IOException, InterruptedException {
        String imageIdentifier = runCommandAndCapture(
                "Local application image lookup",
                "docker",
                "image",
                "inspect",
                "--format={{.Id}}",
                "shared-calendar:local").trim();
        validateImageBuildStateValues(imageIdentifier, inputDigest);
        Files.createDirectories(LOCAL_IMAGE_BUILD_STATE_PATH.getParent());
        Path temporaryStatePath = Files.createTempFile(
                LOCAL_IMAGE_BUILD_STATE_PATH.getParent(),
                "shared-calendar-local-",
                ".tmp");
        Throwable primaryFailure = null;
        try {
            Files.writeString(
                    temporaryStatePath,
                    imageBuildStateContents(imageIdentifier, inputDigest),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporaryStatePath,
                        LOCAL_IMAGE_BUILD_STATE_PATH,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporaryStatePath,
                        LOCAL_IMAGE_BUILD_STATE_PATH,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException | Error exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(temporaryStatePath);
            } catch (IOException cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void validateStableImageBuildInputs(String inputDigestBeforeBuild, String inputDigestAfterBuild) {
        validateImageBuildInputDigest(inputDigestBeforeBuild);
        validateImageBuildInputDigest(inputDigestAfterBuild);
        if (!inputDigestBeforeBuild.equals(inputDigestAfterBuild)) {
            throw new IllegalStateException(
                    "Production-image inputs changed while Docker was building. Re-run the image build from stable sources.");
        }
    }

    private static void verifyRecordedImageBuildState(Path projectDirectory, String imageIdentifier)
            throws IOException {
        Path statePath = projectDirectory.resolve(".build/image-state/shared-calendar-local.txt");
        if (!Files.isRegularFile(statePath)) {
            throw new IllegalStateException(
                    "The local image has no verified build-state record. Rebuild it with 'mise run docker-build'.");
        }
        validateRecordedImageBuildState(
                Files.readString(statePath),
                imageIdentifier,
                imageBuildInputDigest(projectDirectory));
    }

    static void validateRecordedImageBuildState(
            String stateContents,
            String expectedImageIdentifier,
            String expectedInputDigest) {
        validateImageBuildStateValues(expectedImageIdentifier, expectedInputDigest);
        if (!imageBuildStateContents(expectedImageIdentifier, expectedInputDigest).equals(stateContents)) {
            throw new IllegalStateException(
                    "The local shared-calendar:local image does not match current Docker build inputs. "
                            + "Rebuild and restart it before verification.");
        }
    }

    private static void validateImageBuildStateValues(String imageIdentifier, String inputDigest) {
        if (imageIdentifier == null
                || !imageIdentifier.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException("The local image build state contains invalid identity values.");
        }
        validateImageBuildInputDigest(inputDigest);
    }

    private static void validateImageBuildInputDigest(String inputDigest) {
        if (inputDigest == null || !inputDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("The local image build state contains an invalid input digest.");
        }
    }

    private static String imageBuildStateContents(String imageIdentifier, String inputDigest) {
        return "image-id=" + imageIdentifier + System.lineSeparator()
                + "inputs-sha256=" + inputDigest + System.lineSeparator();
    }

    static String imageBuildInputDigest(Path projectDirectory) throws IOException {
        return imageBuildInputDigest(projectDirectory, System.getenv());
    }

    static String imageBuildInputDigest(
            Path projectDirectory,
            Map<String, String> environment) throws IOException {
        List<Path> buildInputFiles = new ArrayList<>();
        for (Path input : List.of(
                projectDirectory.resolve("Dockerfile"),
                projectDirectory.resolve(".dockerignore"),
                projectDirectory.resolve(".mvn"),
                projectDirectory.resolve("mvnw"),
                projectDirectory.resolve("pom.xml"),
                projectDirectory.resolve("docker-compose.yml"),
                projectDirectory.resolve("src/main"))) {
            if (!Files.exists(input)) {
                throw new IllegalStateException("A required production-image build input is missing: " + input + ".");
            }
            try (var paths = Files.isDirectory(input) ? Files.walk(input) : java.util.stream.Stream.of(input)) {
                buildInputFiles.addAll(paths.filter(Files::isRegularFile).toList());
            }
        }
        buildInputFiles.sort((firstPath, secondPath) -> normalizedRelativePath(projectDirectory, firstPath)
                .compareTo(normalizedRelativePath(projectDirectory, secondPath)));

        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        for (Path buildInputFile : buildInputFiles) {
            String relativePath = normalizedRelativePath(projectDirectory, buildInputFile);
            digest.update(relativePath.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(Files.size(buildInputFile)).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var inputStream = Files.newInputStream(buildInputFile)) {
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            digest.update((byte) 0);
        }
        String deploymentRevision = environment.get("RAILWAY_GIT_COMMIT_SHA");
        if (deploymentRevision == null) {
            deploymentRevision = "";
        }
        digest.update("build-argument:RAILWAY_GIT_COMMIT_SHA".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(deploymentRevision.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String normalizedRelativePath(Path projectDirectory, Path path) {
        return projectDirectory.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    static void verifyDevelopmentBuildIsCurrent(Path projectDirectory) throws IOException {
        Path descriptor = projectDirectory.resolve(
                ".liberty/user/servers/defaultServer/apps/shared-calendar.war.xml");
        Path compiledClasses = projectDirectory.resolve(".build/development/classes");
        Path webApplicationSource = projectDirectory.resolve("src/main/webapp");
        Path generatedServerConfiguration = projectDirectory.resolve(
                ".liberty/user/servers/defaultServer/server.xml");
        Path sourceServerConfiguration = projectDirectory.resolve("src/main/liberty/config/server.xml");
        if (!Files.isRegularFile(descriptor)
                || !Files.isDirectory(compiledClasses)
                || !Files.isRegularFile(generatedServerConfiguration)) {
            throw new IllegalStateException(
                    "The Liberty development build is missing. Start it with 'mise run dev' before verification.");
        }

        String descriptorContents = Files.readString(descriptor);
        if (!descriptorContents.contains(webApplicationSource.toAbsolutePath().normalize().toString())
                || !descriptorContents.contains(compiledClasses.toAbsolutePath().normalize().toString())) {
            throw new IllegalStateException(
                    "The running Liberty loose application does not point at this repository's current build outputs.");
        }
        if (Files.mismatch(sourceServerConfiguration, generatedServerConfiguration) != -1) {
            throw new IllegalStateException(
                    "The Liberty development server configuration is stale. Restart 'mise run dev'.");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", exception);
        }
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
