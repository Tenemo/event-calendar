import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class CalendarToolTest {
    private static int completedTestCount;

    private CalendarToolTest() {}

    public static void main(String[] arguments) throws Exception {
        run("parses supported commands", CalendarToolTest::parsesSupportedCommands);
        run("rejects removed and malformed commands", CalendarToolTest::rejectsRemovedAndMalformedCommands);
        run("locks mutating workspace operations", CalendarToolTest::locksMutatingWorkspaceOperations);
        run("prevents concurrent workspace operations", CalendarToolTest::preventsConcurrentWorkspaceOperations);
        run("builds portable command lines", CalendarToolTest::buildsPortableCommandLines);
        run("resolves verification endpoints", CalendarToolTest::resolvesVerificationEndpoints);
        run("validates browser and port configuration", CalendarToolTest::validatesBrowserAndPortConfiguration);
        run("validates health and runtime logs", CalendarToolTest::validatesHealthAndRuntimeLogs);
        run("validates deployment revisions", CalendarToolTest::validatesDeploymentRevisions);
        run("validates loopback port mappings", CalendarToolTest::validatesLoopbackPortMappings);
        run("validates Compose database configuration", CalendarToolTest::validatesComposeDatabaseConfiguration);
        run("builds PostgreSQL commands", CalendarToolTest::buildsPostgreSqlCommands);
        run("resolves database defaults", CalendarToolTest::resolvesDatabaseDefaults);

        System.out.println("Calendar tool tests passed: " + completedTestCount + ".");
    }

    private static void parsesSupportedCommands() {
        List<String> commandsWithoutArguments = List.of(
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
                "verify-preview-deployment",
                "end-to-end",
                "end-to-end-shared",
                "end-to-end-cross-browser-smoke",
                "wait-for-app",
                "verify-local",
                "docker-build",
                "image-scan",
                "docker-up");
        for (String command : commandsWithoutArguments) {
            CalendarTool.ToolInvocation invocation = CalendarTool.parseInvocation(new String[] {command});
            assertEquals(command, invocation.command(), "Parsed command");
            assertEquals(List.of(), invocation.arguments(), "Command arguments");
        }

        CalendarTool.ToolInvocation bootstrap = CalendarTool.parseInvocation(
                new String[] {"verify-bootstrap-registration", "--reuse-image"});
        assertEquals(List.of("--reuse-image"), bootstrap.arguments(), "Bootstrap option");
        expectThrows(
                UnsupportedOperationException.class,
                () -> bootstrap.arguments().add("unexpected"));
    }

    private static void rejectsRemovedAndMalformedCommands() {
        expectThrows(CalendarTool.UsageException.class, () -> CalendarTool.parseInvocation(new String[0]));
        expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(new String[] {"unknown"}));
        for (String removedCommand : List.of(
                "backup-postgres",
                "restore-postgres",
                "verify-backup-restore",
                "lighthouse",
                "end-to-end-calendar-link-throttle")) {
            expectThrows(
                    CalendarTool.UsageException.class,
                    () -> CalendarTool.parseInvocation(new String[] {removedCommand}));
        }
        expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(new String[] {"package", "unexpected"}));
        expectThrows(
                CalendarTool.UsageException.class,
                () -> CalendarTool.parseInvocation(
                        new String[] {"verify-bootstrap-registration", "--skip-build"}));
    }

    private static void locksMutatingWorkspaceOperations() {
        assertFalse(CalendarTool.requiresWorkspaceOperationLock("help"), "Help should not lock the workspace");
        assertFalse(CalendarTool.requiresWorkspaceOperationLock("dev"), "Development mode should not hold the lock");
        for (String command : List.of("package", "format", "end-to-end", "docker-build", "verify-local")) {
            assertTrue(
                    CalendarTool.requiresWorkspaceOperationLock(command),
                    command + " should lock the workspace");
        }
    }

    private static void preventsConcurrentWorkspaceOperations() throws Exception {
        Path lockDirectory = Files.createTempDirectory("calendar-tool-lock-");
        Path lockPath = lockDirectory.resolve("workspace.lock");
        try (CalendarTool.WorkspaceOperationLock ignored = CalendarTool.acquireWorkspaceOperationLock(lockPath)) {
            expectThrows(
                    IllegalStateException.class,
                    () -> CalendarTool.acquireWorkspaceOperationLock(lockPath));
        } finally {
            Files.deleteIfExists(lockPath);
            Files.deleteIfExists(lockDirectory);
        }
    }

    private static void buildsPortableCommandLines() {
        assertArrayEquals(
                new String[] {"mvnw", "-B", "-ntp", "spotless:apply"},
                CalendarTool.formatCommand(),
                "Formatting command");
        assertArrayEquals(
                new String[] {"npm", "ci", "--ignore-scripts"},
                CalendarTool.npmInstallCommand(),
                "npm installation command");
        assertArrayEquals(
                new String[] {"npm", "run", "lint:css"},
                CalendarTool.npmCssLintCommand(),
                "CSS lint command");
        assertArrayEquals(
                new String[] {
                    "mvnw",
                    "-Pend-to-end",
                    "test-compile",
                    "failsafe:integration-test",
                    "failsafe:verify"
                },
                CalendarToolVerification.endToEndMavenCommand(null),
                "Shared end-to-end command");
        assertArrayEquals(
                new String[] {
                    "docker", "compose", "--profile", "test", "up", "-d", "--force-recreate", "--no-build",
                    "postgres", "web"
                },
                CalendarToolVerification.composeUpCommand("test", "postgres", "web"),
                "Compose startup command");
    }

    private static void resolvesVerificationEndpoints() {
        CalendarToolVerification.VerificationEndpoints defaults =
                CalendarToolVerification.endToEndVerificationEndpoints(Map.of());
        assertEquals("9082", defaults.healthControlPort(), "Default HTTP port");
        assertEquals("9445", defaults.httpsPort(), "Default HTTPS port");
        assertEquals("55433", defaults.databasePort(), "Default database port");
        assertEquals("https://localhost:9445", defaults.applicationBaseUri().toString(), "Base URL");

        CalendarToolVerification.VerificationEndpoints configured =
                CalendarToolVerification.endToEndVerificationEndpoints(Map.of(
                        "END_TO_END_VERIFICATION_APPLICATION_PORT", "19082",
                        "END_TO_END_VERIFICATION_HTTPS_PORT", "19445",
                        "END_TO_END_VERIFICATION_DATABASE_PORT", "55434"));
        assertEquals("19082", configured.healthControlPort(), "Configured HTTP port");
        assertEquals("19445", configured.httpsPort(), "Configured HTTPS port");
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.endToEndVerificationEndpoints(Map.of(
                        "END_TO_END_VERIFICATION_APPLICATION_PORT", "19082",
                        "END_TO_END_VERIFICATION_HTTPS_PORT", "19082")));
    }

    private static void validatesBrowserAndPortConfiguration() {
        assertEquals("chromium", CalendarToolVerification.configuredBrowserName(Map.of()), "Default browser");
        assertEquals(
                "firefox",
                CalendarToolVerification.configuredBrowserName(Map.of("BROWSER", " Firefox ")),
                "Configured browser");
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.configuredBrowserName(Map.of("BROWSER", "opera")));
        assertEquals(
                "9443",
                CalendarToolVerification.environmentPortValue(Map.of(), "HTTPS_PORT", "9443"),
                "Default port");
        for (String invalidPort : List.of("0", "65536", "not-a-port")) {
            expectThrows(
                    IllegalArgumentException.class,
                    () -> CalendarToolVerification.environmentPortValue(
                            Map.of("HTTPS_PORT", invalidPort), "HTTPS_PORT", "9443"));
        }
    }

    private static void validatesHealthAndRuntimeLogs() {
        CalendarToolVerification.validateHealthResponse(
                java.net.URI.create("http://localhost:9080/health"),
                200,
                " ok\n");
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateHealthResponse(
                        java.net.URI.create("http://localhost:9080/health"),
                        503,
                        "unavailable"));
        CalendarToolVerification.validateApplicationRuntimeLogs("CWWKF0011I: server ready");
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateApplicationRuntimeLogs("SEVERE: deployment failed"));
    }

    private static void validatesDeploymentRevisions() {
        String revision = "a".repeat(40);
        CalendarToolVerification.validateExpectedDeploymentRevision(null, null);
        CalendarToolVerification.validateExpectedDeploymentRevision(revision.toUpperCase(), revision);
        expectThrows(
                IllegalArgumentException.class,
                () -> CalendarToolVerification.validateExpectedDeploymentRevision("short", revision));
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateExpectedDeploymentRevision(revision, "b".repeat(40)));
    }

    private static void validatesLoopbackPortMappings() {
        CalendarToolVerification.validateExpectedLoopbackPortMapping(
                "127.0.0.1:9080\n[::1]:9080",
                9080,
                "application HTTP");
        assertTrue(
                CalendarToolVerification.composePortMappingIncludes("127.0.0.1:9080", 9080),
                "Expected port mapping");
        assertFalse(
                CalendarToolVerification.composePortMappingIncludes("127.0.0.1:9081", 9080),
                "Unexpected port mapping");
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolVerification.validateComposePortMappingsAreLoopbackOnly("0.0.0.0:9080"));
    }

    private static void validatesComposeDatabaseConfiguration() {
        CalendarToolPostgreSql.validateComposeApplicationDatabaseConfiguration(
                "postgres\n5432\ncalendar\ncalendar\nshared-calendar-compose-web-9080\n",
                "calendar",
                "calendar",
                "shared-calendar-compose-web-9080");
        expectThrows(
                IllegalStateException.class,
                () -> CalendarToolPostgreSql.validateComposeApplicationDatabaseConfiguration(
                        "localhost\n5432\ncalendar\ncalendar\nshared-calendar-compose-web-9080\n",
                        "calendar",
                        "calendar",
                        "shared-calendar-compose-web-9080"));
    }

    private static void buildsPostgreSqlCommands() {
        String[] command = CalendarToolPostgreSql.composeSqlCommand(
                "postgres",
                "calendar",
                "calendar",
                "select 1;");
        assertEquals("docker", command[0], "PostgreSQL command executable");
        assertTrue(Arrays.asList(command).contains("ON_ERROR_STOP=1"), "SQL errors must stop verification");
        assertTrue(Arrays.asList(command).contains("select 1;"), "SQL statement");
    }

    private static void resolvesDatabaseDefaults() {
        assertEquals("calendar", CalendarToolPostgreSql.databaseName(Map.of()), "Default database name");
        assertEquals("calendar", CalendarToolPostgreSql.databaseUser(Map.of()), "Default database user");
        assertEquals(
                "friends",
                CalendarToolPostgreSql.databaseName(Map.of("PGDATABASE", " friends ")),
                "Configured database name");
        assertEquals(
                "owner",
                CalendarToolPostgreSql.databaseUser(Map.of("PGUSER", " owner ")),
                "Configured database user");
    }

    private static void run(String description, ThrowingAction action) throws Exception {
        try {
            action.run();
            completedTestCount++;
        } catch (Throwable failure) {
            throw new AssertionError("Failed: " + description, failure);
        }
    }

    private static void assertArrayEquals(String[] expected, String[] actual, String description) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(
                    description + " expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + " expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static <FailureType extends Throwable> FailureType expectThrows(
            Class<FailureType> expectedType,
            ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expectedType.isInstance(failure)) {
                return expectedType.cast(failure);
            }
            throw new AssertionError(
                    "Expected " + expectedType.getName() + " but got " + failure.getClass().getName(),
                    failure);
        }
        throw new AssertionError("Expected " + expectedType.getName() + " to be thrown.");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
