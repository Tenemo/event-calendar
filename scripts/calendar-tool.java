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
    private static final Path LIBERTY_RESOURCES_DIRECTORY =
            PROJECT_DIRECTORY.resolve("src/main/liberty/config/resources");
    private static final URI HEALTH_URI = URI.create("http://localhost:9080/health");

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
        Files.createDirectories(LIBERTY_RESOURCES_DIRECTORY);
        runCommand("Liberty resource preparation", "mvn", "-q", "generate-resources");
    }

    private static void startDatabase() throws IOException, InterruptedException {
        runCommand("PostgreSQL startup", "docker", "compose", "up", "-d", "postgres");
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
        checkApplicationHealth();
        runCommand(
                "PostgreSQL connection check",
                "docker",
                "compose",
                "exec",
                "-T",
                "postgres",
                "psql",
                "-U",
                "calendar",
                "-d",
                "calendar",
                "-c",
                "select current_database(), current_user;");
    }

    private static void checkApplicationHealth() throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(HEALTH_URI)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IOException("Health check failed. Start the app with 'mise run dev' before running verify-local.", exception);
        }

        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Health check failed with HTTP " + statusCode + ".");
        }

        System.out.println("Health check returned HTTP " + statusCode + ".");
    }

    private static void requireProjectDirectory() {
        if (!Files.isRegularFile(PROJECT_DIRECTORY.resolve("pom.xml"))) {
            throw new IllegalStateException("Run this tool from the repository root.");
        }
    }

    private static void runCommand(String description, String... command)
            throws IOException, InterruptedException {
        List<String> commandLine = new ArrayList<>(Arrays.asList(command));
        commandLine.set(0, platformExecutableName(commandLine.get(0)));
        ProcessBuilder processBuilder = new ProcessBuilder(commandLine)
                .directory(PROJECT_DIRECTORY.toFile())
                .inheritIO();

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new IOException("Required command '" + commandLine.getFirst() + "' was not found on PATH.", exception);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    private static void printUsage() {
        String executableName = isWindows() ? "java scripts\\calendar-tool.java" : "java scripts/calendar-tool.java";
        System.err.println("Usage: " + executableName + " <command>");
        System.err.println("Commands: check-toolchain, prepare-liberty-dev, setup, db, dev, package, verify-local");
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
