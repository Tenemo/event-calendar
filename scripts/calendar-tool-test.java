import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class CalendarToolTest {
    private static int completedTestCount;

    public static void main(String[] arguments) throws Exception {
        run("parses existing and CI commands", CalendarToolTest::parsesExistingAndCiCommands);
        run("rejects unknown commands and malformed arguments", CalendarToolTest::rejectsInvalidInvocations);
        run("checks the complete required toolchain", CalendarToolTest::checksRequiredToolchain);
        run("resolves pinned subprocess tools through mise", CalendarToolTest::resolvesPinnedSubprocessTools);
        run("validates browser and port environment values", CalendarToolTest::validatesEnvironmentValues);
        run("constructs canonical health URLs", CalendarToolTest::constructsHealthUrls);
        run("separates HTTPS browser and HTTP health endpoints", CalendarToolTest::separatesE2eEndpoints);
        run("keeps verification diagnostics free of secrets", CalendarToolTest::keepsDiagnosticsSecretFree);
        run("validates health responses precisely", CalendarToolTest::validatesHealthResponses);
        run("polls until the application becomes healthy", CalendarToolTest::pollsUntilHealthy);
        run("reports the last readiness failure on timeout", CalendarToolTest::reportsReadinessTimeoutCause);
        run("propagates readiness interruption", CalendarToolTest::propagatesReadinessInterruption);
        run("constructs portable quality commands", CalendarToolTest::constructsQualityCommands);
        run("uses the Compose PostgreSQL image as one source", CalendarToolTest::usesComposePostgresqlImage);
        run("constructs isolated verification commands", CalendarToolTest::constructsVerificationCommands);
        run("rejects application runtime failures", CalendarToolTest::rejectsApplicationRuntimeFailures);

        System.out.println("Calendar tool self-tests passed: " + completedTestCount + ".");
    }

    private static void parsesExistingAndCiCommands() {
        for (String command : List.of(
                "help",
                "--help",
                "setup",
                "db",
                "dev",
                "package",
                "tooling-self-test",
                "format",
                "static-analysis",
                "verify-reproducible-build",
                "lint-css",
                "e2e",
                "e2e-shared",
                "e2e-cross-browser-smoke",
                "e2e-calendar-link-throttle",
                "wait-for-app",
                "verify-local",
                "docker-build",
                "image-scan",
                "docker-up",
                "verify-backup-restore")) {
            CalendarTool.ToolInvocation invocation = CalendarTool.parseInvocation(new String[] {command});
            assertEquals(command, invocation.command(), "Parsed command");
            assertEquals(List.of(), invocation.arguments(), "Argument-free command arguments");
        }

        CalendarTool.ToolInvocation bootstrap = CalendarTool.parseInvocation(
                new String[] {"verify-bootstrap-registration", "--reuse-image"});
        assertEquals(List.of("--reuse-image"), bootstrap.arguments(), "Bootstrap reuse option");

        CalendarTool.ToolInvocation backup = CalendarTool.parseInvocation(
                new String[] {"backup-postgres", ".build/backups/with spaces.dump"});
        assertEquals(List.of(".build/backups/with spaces.dump"), backup.arguments(), "Backup output path");

        CalendarTool.ToolInvocation restore = CalendarTool.parseInvocation(
                new String[] {"restore-postgres", "backup.dump", "calendar"});
        assertEquals(List.of("backup.dump", "calendar"), restore.arguments(), "Restore arguments");
        expectThrows(UnsupportedOperationException.class, () -> restore.arguments().add("unexpected"));
    }

    private static void rejectsInvalidInvocations() {
        CalendarTool.UsageException missingCommand = expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(new String[0]));
        assertEquals(null, missingCommand.getMessage(), "Missing command message");

        assertContains(
                expectThrows(
                                CalendarTool.UsageException.class,
                                () -> CalendarTool.parseInvocation(new String[] {"invented"}))
                        .getMessage(),
                "Unknown command",
                "Unknown command failure");
        assertContains(
                expectThrows(
                                CalendarTool.UsageException.class,
                                () -> CalendarTool.parseInvocation(new String[] {"e2e", "unexpected"}))
                        .getMessage(),
                "Invalid arguments",
                "Extra argument failure");
        expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(
                        new String[] {"verify-bootstrap-registration", "--skip-build"}));
        expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(new String[] {"restore-postgres", "backup.dump"}));
        expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(
                        new String[] {"backup-postgres", "first.dump", "second.dump"}));
    }

    private static void resolvesPinnedSubprocessTools() {
        for (String managedCommand : List.of("actionlint", "node", "npm", "shellcheck", "trivy")) {
            assertEquals(
                    List.of("mise", "exec", "--", managedCommand, "--version"),
                    CalendarToolProcessRunner.resolvedCommandLine(managedCommand, "--version"),
                    managedCommand + " command resolution");
        }

        assertEquals(
                List.of("docker", "compose", "version"),
                CalendarToolProcessRunner.resolvedCommandLine("docker", "compose", "version"),
                "Unmanaged Docker command resolution");
        expectThrows(
                IllegalArgumentException.class,
                CalendarToolProcessRunner::resolvedCommandLine);
    }

    private static void validatesEnvironmentValues() {
        assertEquals("chromium", CalendarToolVerification.configuredBrowserName(Map.of()), "Default browser");
        assertEquals(
                "firefox",
                CalendarToolVerification.configuredBrowserName(Map.of("BROWSER", "  FIREFOX  ")),
                "Normalized browser");
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.configuredBrowserName(Map.of("BROWSER", "edge")));

        assertEquals(
                "9080",
                CalendarToolVerification.environmentPortValue(Map.of(), "PORT", "9080"),
                "Default port");
        assertEquals(
                "65535",
                CalendarToolVerification.environmentPortValue(Map.of("PORT", " 65535 "), "PORT", "9080"),
                "Highest valid port");
        assertEquals(
                "80",
                CalendarToolVerification.environmentPortValue(Map.of("PORT", "00080"), "PORT", "9080"),
                "Canonical port representation");
        for (String invalidPort : List.of("0", "65536", "-1", "1.5", "http", "999999999999")) {
            assertContains(
                    expectThrows(
                                    IllegalArgumentException.class,
                                    () -> CalendarToolVerification.environmentPortValue(
                                            Map.of("PORT", invalidPort), "PORT", "9080"))
                            .getMessage(),
                    "PORT",
                    "Invalid port failure");
        }
        assertEquals(
                "fallback",
                CalendarToolVerification.environmentValueOrDefault(Map.of("VALUE", "  "), "VALUE", "fallback"),
                "Blank environment fallback");
    }

    private static void checksRequiredToolchain() {
        List<CalendarTool.ToolCheck> toolChecks = CalendarTool.setupToolChecks();
        assertEquals(10, toolChecks.size(), "Required tool check count");
        assertContains(
                toolChecks.get(0).command()[0].toLowerCase(),
                "java",
                "Java runtime executable");
        assertArrayEquals(new String[] {"mvnw", "--version"}, toolChecks.get(1).command(), "Maven check");
        assertArrayEquals(new String[] {"docker", "--version"}, toolChecks.get(2).command(), "Docker check");
        assertArrayEquals(
                new String[] {"docker", "compose", "version"},
                toolChecks.get(3).command(),
                "Docker Compose check");
        assertArrayEquals(new String[] {"mise", "--version"}, toolChecks.get(4).command(), "mise check");
        assertArrayEquals(new String[] {"node", "--version"}, toolChecks.get(5).command(), "Node.js check");
        assertArrayEquals(new String[] {"npm", "--version"}, toolChecks.get(6).command(), "npm check");
        assertArrayEquals(new String[] {"trivy", "--version"}, toolChecks.get(7).command(), "Trivy check");
        assertArrayEquals(
                new String[] {"actionlint", "-version"},
                toolChecks.get(8).command(),
                "actionlint check");
        assertArrayEquals(
                new String[] {"shellcheck", "--version"},
                toolChecks.get(9).command(),
                "ShellCheck check");

        String[] mutableCopy = toolChecks.get(7).command();
        mutableCopy[0] = "replaced";
        assertArrayEquals(
                new String[] {"trivy", "--version"},
                toolChecks.get(7).command(),
                "Tool check command immutability");
    }

    private static void constructsHealthUrls() {
        assertEquals(
                URI.create("http://localhost:12345/health"),
                CalendarToolVerification.applicationHealthUri(Map.of("PORT", "12345")),
                "Default local health URL");
        assertEquals(
                URI.create("https://calendar.example/app/health"),
                CalendarToolVerification.applicationHealthUri(
                        Map.of("APP_BASE_URL", " https://calendar.example/app/// ")),
                "Configured health URL");

        for (String invalidBaseUrl : List.of(
                "calendar.example",
                "ftp://calendar.example",
                "https://user:secret@calendar.example",
                "https://calendar.example?check=true",
                "https://calendar.example/#status",
                "http://")) {
            expectThrows(
                    IllegalArgumentException.class,
                    () -> CalendarToolVerification.applicationHealthUri(Map.of("APP_BASE_URL", invalidBaseUrl)));
        }
    }

    private static void validatesHealthResponses() {
        URI healthUri = URI.create("https://calendar.example/health");
        CalendarToolVerification.validateHealthResponse(healthUri, 200, "  ok\n");

        IllegalStateException unavailable = expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateHealthResponse(healthUri, 503, "unavailable"));
        assertContains(unavailable.getMessage(), "HTTP 503", "Unavailable status failure");
        assertContains(unavailable.getMessage(), healthUri.toString(), "Health URL in failure");

        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateHealthResponse(healthUri, 200, "healthy"));
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateHealthResponse(healthUri, 200, null));
    }

    private static void separatesE2eEndpoints() {
        CalendarToolVerification.E2eVerificationEndpoints defaults =
                CalendarToolVerification.e2eVerificationEndpoints(Map.of());
        assertEquals("9082", defaults.healthControlPort(), "Default HTTP health port");
        assertEquals("9445", defaults.httpsPort(), "Default HTTPS browser port");
        assertEquals("55433", defaults.databasePort(), "Default PostgreSQL port");
        assertEquals(
                URI.create("https://localhost:9445"),
                defaults.applicationBaseUri(),
                "Default HTTPS browser base URL");
        assertEquals(
                URI.create("http://localhost:9082/health"),
                defaults.healthControlUri(),
                "Default HTTP health-control URL");

        CalendarToolVerification.E2eVerificationEndpoints configured =
                CalendarToolVerification.e2eVerificationEndpoints(Map.of(
                "E2E_VERIFICATION_APPLICATION_PORT", "19082",
                "E2E_VERIFICATION_HTTPS_PORT", "19445",
                "E2E_VERIFICATION_DATABASE_PORT", "15433"));
        assertEquals(
                URI.create("https://localhost:19445"),
                configured.applicationBaseUri(),
                "Configured HTTPS browser base URL");
        assertEquals(
                URI.create("http://localhost:19082/health"),
                configured.healthControlUri(),
                "Configured HTTP health-control URL");

        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.e2eVerificationEndpoints(Map.of(
                        "E2E_VERIFICATION_APPLICATION_PORT", "19445",
                        "E2E_VERIFICATION_HTTPS_PORT", "19445")));
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.e2eVerificationEndpoints(Map.of(
                        "E2E_VERIFICATION_HTTPS_PORT", "15433",
                        "E2E_VERIFICATION_DATABASE_PORT", "15433")));

        CalendarToolVerification.BootstrapVerificationEndpoints bootstrapDefaults =
                CalendarToolVerification.bootstrapVerificationEndpoints(Map.of());
        assertEquals("9081", bootstrapDefaults.healthControlPort(), "Default bootstrap HTTP health port");
        assertEquals("9444", bootstrapDefaults.httpsPort(), "Default bootstrap HTTPS browser port");
        assertEquals("55432", bootstrapDefaults.databasePort(), "Default bootstrap PostgreSQL port");
        assertEquals(
                URI.create("https://localhost:9444"),
                bootstrapDefaults.applicationBaseUri(),
                "Default bootstrap HTTPS browser base URL");
        assertEquals(
                URI.create("http://localhost:9081/health"),
                bootstrapDefaults.healthControlUri(),
                "Default bootstrap HTTP health-control URL");

        CalendarToolVerification.BootstrapVerificationEndpoints bootstrapConfigured =
                CalendarToolVerification.bootstrapVerificationEndpoints(Map.of(
                        "BOOTSTRAP_VERIFICATION_APPLICATION_PORT", "19081",
                        "BOOTSTRAP_VERIFICATION_HTTPS_PORT", "19444",
                        "BOOTSTRAP_VERIFICATION_DATABASE_PORT", "15432"));
        assertEquals(
                URI.create("https://localhost:19444"),
                bootstrapConfigured.applicationBaseUri(),
                "Configured bootstrap HTTPS browser base URL");
        assertEquals(
                URI.create("http://localhost:19081/health"),
                bootstrapConfigured.healthControlUri(),
                "Configured bootstrap HTTP health-control URL");

        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.bootstrapVerificationEndpoints(Map.of(
                        "BOOTSTRAP_VERIFICATION_APPLICATION_PORT", "19444",
                        "BOOTSTRAP_VERIFICATION_HTTPS_PORT", "19444")));
    }

    private static void keepsDiagnosticsSecretFree() {
        String diagnosticContext = CalendarToolVerification.verificationDiagnosticContext(
                "e2e-verification",
                Map.of(
                        "BROWSER", "webkit",
                        "APP_BASE_URL", "https://localhost:9445",
                        "PGPASSWORD", "database-secret",
                        "BOOTSTRAP_VERIFICATION_INVITATION_TOKEN", "invitation-secret"),
                URI.create("http://localhost:9082/health"));

        assertContains(diagnosticContext, "e2e-verification", "Diagnostic profile");
        assertContains(diagnosticContext, "webkit", "Diagnostic browser");
        assertContains(diagnosticContext, "https://localhost:9445", "Diagnostic browser URL");
        assertContains(diagnosticContext, "http://localhost:9082/health", "Diagnostic health URL");
        assertDoesNotContain(diagnosticContext, "database-secret", "Database password redaction");
        assertDoesNotContain(diagnosticContext, "invitation-secret", "Invitation token redaction");
    }

    private static void pollsUntilHealthy() throws Exception {
        URI healthUri = URI.create("http://localhost:9080/health");
        long[] currentNanoseconds = {0};
        int[] attemptCount = {0};
        List<Long> sleepDurations = new ArrayList<>();

        CalendarToolVerification.waitForApplication(
                healthUri,
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                requestedUri -> {
                    assertEquals(healthUri, requestedUri, "Polled health URL");
                    attemptCount[0]++;
                    if (attemptCount[0] < 3) {
                        throw new IOException("not ready " + attemptCount[0]);
                    }
                },
                () -> currentNanoseconds[0],
                milliseconds -> {
                    sleepDurations.add(milliseconds);
                    currentNanoseconds[0] += Duration.ofMillis(milliseconds).toNanos();
                });

        assertEquals(3, attemptCount[0], "Readiness attempts");
        assertEquals(List.of(100L, 100L), sleepDurations, "Readiness poll intervals");
    }

    private static void reportsReadinessTimeoutCause() {
        long[] currentNanoseconds = {0};
        int[] attemptCount = {0};
        IOException finalFailure = new IOException("database remains unavailable");

        IllegalStateException timeout = expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.waitForApplication(
                        URI.create("http://localhost:9080/health"),
                        Duration.ofMillis(250),
                        Duration.ofMillis(100),
                        ignored -> {
                            attemptCount[0]++;
                            throw finalFailure;
                        },
                        () -> currentNanoseconds[0],
                        milliseconds -> currentNanoseconds[0] += Duration.ofMillis(milliseconds).toNanos()));

        assertEquals(3, attemptCount[0], "Timeout attempt count");
        assertSame(finalFailure, timeout.getCause(), "Timeout cause");
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.waitForApplication(
                        URI.create("http://localhost/health"),
                        Duration.ZERO,
                        Duration.ofMillis(1),
                        ignored -> {},
                        () -> 0,
                        ignored -> {}));
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.waitForApplication(
                        URI.create("http://localhost/health"),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(-1),
                        ignored -> {},
                        () -> 0,
                        ignored -> {}));
    }

    private static void propagatesReadinessInterruption() {
        InterruptedException interruption = new InterruptedException("stop polling");
        InterruptedException thrown = expectThrows(
                InterruptedException.class,
                () -> CalendarToolVerification.waitForApplication(
                        URI.create("http://localhost/health"),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(10),
                        ignored -> {
                            throw interruption;
                        },
                        () -> 0,
                        ignored -> {
                            throw new AssertionError("Polling must not sleep after interruption.");
                        }));
        assertSame(interruption, thrown, "Readiness interruption");
    }

    private static void constructsQualityCommands() {
        String[] sourceLauncherCommand = CalendarTool.sourceLauncherHelpCommand();
        assertEquals(3, sourceLauncherCommand.length, "Exact source launcher argument count");
        assertContains(
                sourceLauncherCommand[0].toLowerCase(),
                "java",
                "Exact source launcher executable");
        assertArrayEquals(
                new String[] {"scripts/calendar-tool.java", "--help"},
                Arrays.copyOfRange(sourceLauncherCommand, 1, sourceLauncherCommand.length),
                "Exact source launcher arguments");
        assertArrayEquals(
                new String[] {"node", "scripts/railway-config-test.mjs"},
                CalendarTool.railwayConfigurationTestCommand(),
                "Railway configuration contract command");
        assertArrayEquals(
                new String[] {"node", "scripts/build-toolchain-config-test.mjs"},
                CalendarTool.buildToolchainConfigurationTestCommand(),
                "Build toolchain configuration contract command");
        assertArrayEquals(
                new String[] {
                    "mvnw", "-B", "-ntp", "spotless:apply"
                },
                CalendarTool.formatCommand(),
                "Source formatting command");
        assertArrayEquals(
                new String[] {"mvnw", "-B", "-ntp", "-DskipTests", "verify"},
                CalendarTool.staticAnalysisCommand(),
                "Static analysis command");
        assertArrayEquals(
                new String[] {"mvnw", "clean", "install", "-DskipTests"},
                CalendarTool.reproducibleBuildReferenceCommand(),
                "Reproducible build reference command");
        assertArrayEquals(
                new String[] {
                    "mvnw",
                    "clean",
                    "verify",
                    "-DskipTests",
                    "artifact:compare"
                },
                CalendarTool.reproducibleBuildComparisonCommand(),
                "Reproducible build comparison command");
        assertArrayEquals(
                new String[] {"npm", "ci", "--ignore-scripts"},
                CalendarTool.npmInstallCommand(),
                "Node installation command");
        assertArrayEquals(
                new String[] {"npm", "run", "lint:css"},
                CalendarTool.npmCssLintCommand(),
                "CSS lint command");
        assertArrayEquals(
                new String[] {"npm", "audit", "--audit-level=high"},
                CalendarTool.npmAuditCommand(),
                "Node dependency audit command");
        assertArrayEquals(
                new String[] {
                    "trivy",
                    "image",
                    "--format",
                    "cyclonedx",
                    "--scanners",
                    "vuln",
                    "--output",
                    ".build/image-scan/shared-calendar-image.cdx.json",
                    "shared-calendar:local"
                },
                CalendarTool.imageSbomCommand(),
                "Image SBOM command");
        assertArrayEquals(
                new String[] {
                    "trivy",
                    "image",
                    "--exit-code",
                    "1",
                    "--severity",
                    "HIGH,CRITICAL",
                    "--scanners",
                    "vuln",
                    "--format",
                    "json",
                    "--output",
                    ".build/image-scan/shared-calendar-vulnerabilities.json",
                    "--no-progress",
                    "shared-calendar:local"
                },
                CalendarTool.imageScanCommand(),
                "Image scan command");
    }

    private static void usesComposePostgresqlImage() throws Exception {
        String composeFileContents = Files.readString(Path.of("docker-compose.yml"));
        String imageReference = CalendarToolPostgresql.postgresqlClientImageReference(composeFileContents);
        assertContains(imageReference, "postgres:17.10@sha256:", "Canonical PostgreSQL image");

        assertEquals(
                "postgres:17.10@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                CalendarToolPostgresql.postgresqlClientImageReference(
                        "services:\n  database:\n    image: "
                                + "postgres:17.10@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
                                + "  restore:\n    image: '"
                                + "postgres:17.10@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'\n"),
                "Quoted canonical PostgreSQL image");
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolPostgresql.postgresqlClientImageReference(
                        "services:\n  database:\n    image: postgres:17\n"));
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolPostgresql.postgresqlClientImageReference(
                        "services:\n  database:\n    image: "
                                + "postgres:17.10@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n"
                                + "  restore:\n    image: "
                                + "postgres:17.10@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n"));
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolPostgresql.postgresqlClientImageReference(
                        "services:\n  application:\n    image: shared-calendar:local\n"));
    }

    private static void constructsVerificationCommands() {
        List<CalendarToolVerification.EndToEndSelection> sharedSelections =
                CalendarToolVerification.sharedEndToEndSelections();
        assertEquals(2, sharedSelections.size(), "Shared end-to-end lifecycle count");
        assertEquals(null, sharedSelections.get(0).selectedTest(), "Aggregate suite selector");
        assertEquals("e2e-shared", sharedSelections.get(0).diagnosticDirectoryName(), "Aggregate diagnostics");
        assertEquals(
                "CalendarLinkRequestThrottleIT",
                sharedSelections.get(1).selectedTest(),
                "Isolated throttle selector");
        assertEquals(
                "e2e-calendar-link-throttle",
                sharedSelections.get(1).diagnosticDirectoryName(),
                "Isolated throttle diagnostics");

        assertArrayEquals(
                new String[] {
                    "mvnw", "-Pe2e", "test-compile", "failsafe:integration-test", "failsafe:verify"
                },
                CalendarToolVerification.endToEndMavenCommand(null),
                "Full end-to-end command");
        assertArrayEquals(
                new String[] {
                    "mvnw",
                    "-Pe2e",
                    "-Dit.test=CrossBrowserSmokeEndToEndIT",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify"
                },
                CalendarToolVerification.endToEndMavenCommand("CrossBrowserSmokeEndToEndIT"),
                "Cross-browser smoke command");
        assertArrayEquals(
                new String[] {
                    "mvnw",
                    "-Pe2e",
                    "-Dit.test=CalendarLinkRequestThrottleIT",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify"
                },
                CalendarToolVerification.endToEndMavenCommand("CalendarLinkRequestThrottleIT"),
                "Isolated throttle command");
        assertArrayEquals(
                new String[] {
                    "docker",
                    "compose",
                    "--profile",
                    "e2e-verification",
                    "up",
                    "-d",
                    "--force-recreate",
                    "--no-build",
                    "postgres-e2e-verification",
                    "web-e2e-verification"
                },
                CalendarToolVerification.composeUpCommand(
                        "e2e-verification", "postgres-e2e-verification", "web-e2e-verification"),
                "Compose startup command");
        assertArrayEquals(
                new String[] {
                    "docker",
                    "compose",
                    "--profile",
                    "e2e-verification",
                    "logs",
                    "--no-color",
                    "web-e2e-verification",
                    "postgres-e2e-verification"
                },
                CalendarToolVerification.composeLogsCommand(
                        "e2e-verification", "web-e2e-verification", "postgres-e2e-verification"),
                "Compose logs command");
        assertArrayEquals(
                new String[] {
                    "docker",
                    "compose",
                    "--profile",
                    "e2e-verification",
                    "rm",
                    "--force",
                    "--stop",
                    "web-e2e-verification",
                    "postgres-e2e-verification"
                },
                CalendarToolVerification.composeCleanupCommand(
                        "e2e-verification", "web-e2e-verification", "postgres-e2e-verification"),
                "Compose cleanup command");
    }

    private static void rejectsApplicationRuntimeFailures() {
        CalendarToolVerification.validateApplicationRuntimeLogs(null);
        CalendarToolVerification.validateApplicationRuntimeLogs("");
        CalendarToolVerification.validateApplicationRuntimeLogs(
                "CWWKS4105I: LTPA configuration is ready.\n"
                        + "CWWKZ0001I: Application started.");

        List<String> failureSignatures = List.of(
                "SEVERE:",
                "\"loglevel\":\"SEVERE\"",
                "CWWKS4106E",
                "CWWKS4118E",
                "CWWKS4000E",
                "ViewExpiredException",
                "SRVE0777E",
                "FFDC1015I",
                "SESN0008E");
        for (String failureSignature : failureSignatures) {
            IllegalStateException exception = expectThrows(
                    IllegalStateException.class,
                    () -> CalendarToolVerification.validateApplicationRuntimeLogs(
                            "unrelated output\n" + failureSignature + ": runtime failure"));
            assertContains(
                    exception.getMessage(),
                    failureSignature,
                    "Application runtime failure diagnostic");
            assertDoesNotContain(
                    exception.getMessage(),
                    "runtime failure",
                    "Application log content is not copied into diagnostics");
        }

        IllegalStateException combinedFailure = expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateApplicationRuntimeLogs(
                        String.join(" observed\n", failureSignatures) + " observed"));
        for (String failureSignature : failureSignatures) {
            assertContains(
                    combinedFailure.getMessage(),
                    failureSignature,
                    "Combined application runtime failure diagnostic");
        }
    }

    private static void run(String name, TestCase testCase) throws Exception {
        try {
            testCase.run();
            completedTestCount++;
        } catch (Throwable failure) {
            throw new AssertionError("Calendar tool self-test failed: " + name, failure);
        }
    }

    private static void assertArrayEquals(String[] expected, String[] actual, String description) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    description + " expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual) + ".");
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            throw new AssertionError(description + " expected " + expected + " but got " + actual + ".");
        }
    }

    private static void assertSame(Object expected, Object actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + " expected the same object instance.");
        }
    }

    private static void assertContains(String actual, String expectedFragment, String description) {
        if (actual == null || !actual.contains(expectedFragment)) {
            throw new AssertionError(
                    description + " expected text containing '" + expectedFragment + "' but got '" + actual + "'.");
        }
    }

    private static void assertDoesNotContain(String actual, String forbiddenFragment, String description) {
        if (actual != null && actual.contains(forbiddenFragment)) {
            throw new AssertionError(
                    description + " expected text not to contain '" + forbiddenFragment + "' but got '" + actual + "'.");
        }
    }

    private static <ExpectedException extends Throwable> ExpectedException expectThrows(
            Class<ExpectedException> expectedType,
            TestCase testCase) {
        try {
            testCase.run();
        } catch (Throwable failure) {
            if (expectedType.isInstance(failure)) {
                return expectedType.cast(failure);
            }
            throw new AssertionError(
                    "Expected " + expectedType.getSimpleName() + " but got " + failure.getClass().getSimpleName() + ".",
                    failure);
        }
        throw new AssertionError("Expected " + expectedType.getSimpleName() + " but no exception was thrown.");
    }

    @FunctionalInterface
    private interface TestCase {
        void run() throws Exception;
    }
}
