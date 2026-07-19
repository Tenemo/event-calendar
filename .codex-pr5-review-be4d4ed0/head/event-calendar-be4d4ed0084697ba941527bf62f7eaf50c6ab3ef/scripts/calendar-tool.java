import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class CalendarTool extends CalendarToolProcessRunner {
    private static final String JAVA_COMMAND = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    private static final String JAVAC_COMMAND = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac").toString();
    private static final String MAVEN_WRAPPER_COMMAND = "mvnw";
    private static final String NPM_COMMAND = "npm";
    private static final String IMAGE_SBOM_OUTPUT_PATH = ".build/image-scan/shared-calendar-image.cdx.json";
    private static final String IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH =
            ".build/image-scan/shared-calendar-vulnerabilities.json";
    private static final Path IMAGE_SCAN_DIRECTORY = PROJECT_DIRECTORY.resolve(".build/image-scan");
    private static final Path TOOLING_TEST_CLASSES_DIRECTORY = PROJECT_DIRECTORY.resolve(".build/tooling-tests");

    private CalendarTool() {}

    public static void main(String[] arguments) throws Exception {
        requireProjectDirectory();

        ToolInvocation invocation;
        try {
            invocation = parseInvocation(arguments);
        } catch (UsageException exception) {
            if (exception.getMessage() != null) {
                System.err.println(exception.getMessage());
            }
            printUsage();
            System.exit(1);
            return;
        }

        switch (invocation.command()) {
            case "help", "--help" -> printUsage();
            case "setup" -> setup();
            case "db" -> CalendarToolPostgresql.startDatabase();
            case "dev" -> startDevelopmentServer();
            case "package" -> packageApplication();
            case "tooling-self-test" -> runToolingSelfTests();
            case "format" -> formatSources();
            case "static-analysis" -> runStaticAnalysis();
            case "verify-reproducible-build" -> verifyReproducibleBuild();
            case "lint-css" -> lintCss();
            case "e2e" -> CalendarToolVerification.runEndToEndTests();
            case "e2e-shared" -> CalendarToolVerification.runSharedEndToEndTests();
            case "e2e-cross-browser-smoke" -> CalendarToolVerification.runCrossBrowserSmokeEndToEndTests();
            case "e2e-calendar-link-throttle" -> CalendarToolVerification.runIsolatedEndToEndTest(
                    "CalendarLinkRequestThrottleIT",
                    "e2e-calendar-link-throttle");
            case "verify-bootstrap-registration" -> {
                boolean reuseImage = invocation.arguments().contains("--reuse-image");
                CalendarToolVerification.verifyBootstrapRegistrationConcurrency(true, !reuseImage);
            }
            case "wait-for-app" -> CalendarToolVerification.waitForApplication();
            case "verify-local" -> CalendarToolVerification.verifyLocal();
            case "docker-build" -> buildDockerImage();
            case "image-scan" -> scanDockerImage();
            case "docker-up" -> startDockerApplication();
            case "backup-postgres" -> CalendarToolPostgresql.backupPostgres(invocation.arguments().isEmpty()
                    ? null
                    : invocation.arguments().getFirst());
            case "restore-postgres" -> CalendarToolPostgresql.restorePostgres(
                    invocation.arguments().get(0),
                    invocation.arguments().get(1));
            case "verify-backup-restore" -> CalendarToolPostgresql.verifyBackupRestore();
            default -> throw new IllegalStateException("Unhandled command: " + invocation.command());
        }
    }

    static ToolInvocation parseInvocation(String[] arguments) {
        if (arguments.length == 0) {
            throw new UsageException(null);
        }

        String command = arguments[0];
        int minimumArgumentCount;
        int maximumArgumentCount;
        switch (command) {
            case "help", "--help", "setup", "db", "dev", "package", "tooling-self-test", "format",
                    "static-analysis", "verify-reproducible-build", "lint-css", "e2e", "e2e-shared",
                    "e2e-cross-browser-smoke", "e2e-calendar-link-throttle", "wait-for-app", "verify-local",
                    "docker-build", "image-scan", "docker-up", "verify-backup-restore" -> {
                minimumArgumentCount = 0;
                maximumArgumentCount = 0;
            }
            case "verify-bootstrap-registration" -> {
                minimumArgumentCount = 0;
                maximumArgumentCount = 1;
            }
            case "backup-postgres" -> {
                minimumArgumentCount = 0;
                maximumArgumentCount = 1;
            }
            case "restore-postgres" -> {
                minimumArgumentCount = 2;
                maximumArgumentCount = 2;
            }
            default -> throw new UsageException("Unknown command: " + command);
        }

        int suppliedArgumentCount = arguments.length - 1;
        if (suppliedArgumentCount < minimumArgumentCount || suppliedArgumentCount > maximumArgumentCount) {
            throw new UsageException("Invalid arguments for command '" + command + "'.");
        }
        if (command.equals("verify-bootstrap-registration")
                && suppliedArgumentCount == 1
                && !arguments[1].equals("--reuse-image")) {
            throw new UsageException("Invalid arguments for command '" + command + "'.");
        }

        return new ToolInvocation(command, List.copyOf(Arrays.asList(arguments).subList(1, arguments.length)));
    }

    record ToolInvocation(String command, List<String> arguments) {}

    static final class UsageException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private static void setup() throws IOException, InterruptedException {
        String previousHeading = null;
        for (ToolCheck toolCheck : setupToolChecks()) {
            if (!toolCheck.heading().equals(previousHeading)) {
                System.out.println(toolCheck.heading() + ":");
                previousHeading = toolCheck.heading();
            }
            runCommand(toolCheck.description(), toolCheck.command());
        }
    }

    static List<ToolCheck> setupToolChecks() {
        return List.of(
                new ToolCheck("Java runtime", "Java runtime check", new String[] {JAVA_COMMAND, "-version"}),
                new ToolCheck("Maven", "Maven Wrapper check", new String[] {MAVEN_WRAPPER_COMMAND, "--version"}),
                new ToolCheck("Docker", "Docker check", new String[] {"docker", "--version"}),
                new ToolCheck("Docker", "Docker Compose check", new String[] {"docker", "compose", "version"}),
                new ToolCheck("mise", "mise check", new String[] {"mise", "--version"}),
                new ToolCheck("Node.js", "Node.js check", new String[] {"node", "--version"}),
                new ToolCheck("npm", "npm check", new String[] {NPM_COMMAND, "--version"}),
                new ToolCheck("Trivy", "Trivy check", new String[] {"trivy", "--version"}),
                new ToolCheck("actionlint", "actionlint check", new String[] {"actionlint", "-version"}),
                new ToolCheck("ShellCheck", "ShellCheck check", new String[] {"shellcheck", "--version"}));
    }

    record ToolCheck(String heading, String description, String[] command) {
        ToolCheck {
            command = command.clone();
        }

        @Override
        public String[] command() {
            return command.clone();
        }
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

    private static void runToolingSelfTests() throws IOException, InterruptedException {
        Files.createDirectories(TOOLING_TEST_CLASSES_DIRECTORY);
        runCommand(
                "Calendar tool self-test compilation",
                JAVAC_COMMAND,
                "--source-path",
                "scripts",
                "-d",
                TOOLING_TEST_CLASSES_DIRECTORY.toString(),
                "scripts/calendar-tool.java",
                "scripts/calendar-tool-test.java");
        runCommand(
                "Calendar tool self-tests",
                JAVA_COMMAND,
                "-cp",
                TOOLING_TEST_CLASSES_DIRECTORY.toString(),
                "CalendarToolTest");
        runCommand("Calendar tool exact source-launch test", sourceLauncherHelpCommand());
        runCommand("Railway configuration contract test", railwayConfigurationTestCommand());
        runCommand("Build toolchain configuration contract test", buildToolchainConfigurationTestCommand());
    }

    static String[] railwayConfigurationTestCommand() {
        return new String[] {"node", "scripts/railway-config-test.mjs"};
    }

    static String[] sourceLauncherHelpCommand() {
        return new String[] {JAVA_COMMAND, "scripts/calendar-tool.java", "--help"};
    }

    static String[] buildToolchainConfigurationTestCommand() {
        return new String[] {"node", "scripts/build-toolchain-config-test.mjs"};
    }

    private static void runStaticAnalysis() throws IOException, InterruptedException {
        runCommand("Static analysis", staticAnalysisCommand());
    }

    private static void formatSources() throws IOException, InterruptedException {
        runCommand("Source formatting", formatCommand());
    }

    static String[] formatCommand() {
        return new String[] {MAVEN_WRAPPER_COMMAND, "-B", "-ntp", "spotless:apply"};
    }

    static String[] staticAnalysisCommand() {
        return new String[] {MAVEN_WRAPPER_COMMAND, "-B", "-ntp", "-DskipTests", "verify"};
    }

    private static void verifyReproducibleBuild() throws IOException, InterruptedException {
        runCommand("Reproducible build reference", reproducibleBuildReferenceCommand());
        runCommand("Reproducible build comparison", reproducibleBuildComparisonCommand());
    }

    static String[] reproducibleBuildReferenceCommand() {
        return new String[] {MAVEN_WRAPPER_COMMAND, "clean", "install", "-DskipTests"};
    }

    static String[] reproducibleBuildComparisonCommand() {
        return new String[] {MAVEN_WRAPPER_COMMAND, "clean", "verify", "-DskipTests", "artifact:compare"};
    }

    private static void lintCss() throws IOException, InterruptedException {
        runCommand("Node dependency installation", npmInstallCommand());
        runCommand("CSS lint", npmCssLintCommand());
        runCommand("Node dependency vulnerability audit", npmAuditCommand());
    }

    static String[] npmInstallCommand() {
        return new String[] {NPM_COMMAND, "ci", "--ignore-scripts"};
    }

    static String[] npmCssLintCommand() {
        return new String[] {NPM_COMMAND, "run", "lint:css"};
    }

    static String[] npmAuditCommand() {
        return new String[] {NPM_COMMAND, "audit", "--audit-level=high"};
    }

    private static void scanDockerImage() throws IOException, InterruptedException {
        Files.createDirectories(IMAGE_SCAN_DIRECTORY);
        Files.deleteIfExists(PROJECT_DIRECTORY.resolve(IMAGE_SBOM_OUTPUT_PATH));
        Files.deleteIfExists(PROJECT_DIRECTORY.resolve(IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH));
        runCommand("Production image SBOM generation", imageSbomCommand());
        int scanExitCode = runCommandForExitCode(true, imageScanCommand());
        Path vulnerabilityReportPath = PROJECT_DIRECTORY.resolve(IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH);
        if (!Files.isRegularFile(vulnerabilityReportPath) || Files.size(vulnerabilityReportPath) == 0) {
            throw new IllegalStateException(
                    "Production image vulnerability scan did not produce "
                            + IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH + ".");
        }
        if (scanExitCode != 0) {
            throw new IllegalStateException(
                    "Production image scan failed or found HIGH/CRITICAL vulnerabilities. Inspect "
                            + IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH + ".");
        }
        System.out.println(
                "Production image scan found no HIGH/CRITICAL vulnerabilities. Report: "
                        + IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH + ".");
    }

    static String[] imageSbomCommand() {
        return new String[] {
            "trivy",
            "image",
            "--format",
            "cyclonedx",
            "--scanners",
            "vuln",
            "--output",
            IMAGE_SBOM_OUTPUT_PATH,
            "shared-calendar:local"
        };
    }

    static String[] imageScanCommand() {
        return new String[] {
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
            IMAGE_VULNERABILITY_REPORT_OUTPUT_PATH,
            "--no-progress",
            "shared-calendar:local"
        };
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
        CalendarToolVerification.waitForApplication();
    }

    private static void requireProjectDirectory() {
        if (!Files.isRegularFile(PROJECT_DIRECTORY.resolve("pom.xml"))) {
            throw new IllegalStateException("Run this tool from the repository root.");
        }
    }

    private static void printUsage() {
        String executableName = isWindows() ? "java scripts\\calendar-tool.java" : "java scripts/calendar-tool.java";
        System.err.println("Usage: " + executableName + " <command> [arguments]");
        System.err.println(
                "Commands: help, setup, db, dev, package, tooling-self-test, format, static-analysis, "
                        + "verify-reproducible-build, lint-css, e2e, e2e-shared, e2e-cross-browser-smoke, "
                        + "e2e-calendar-link-throttle, verify-bootstrap-registration [--reuse-image], "
                        + "wait-for-app, verify-local, docker-build, image-scan, docker-up, "
                        + "backup-postgres [output-file], "
                        + "restore-postgres <backup-file> <confirmed-database-name>, verify-backup-restore");
    }

}
