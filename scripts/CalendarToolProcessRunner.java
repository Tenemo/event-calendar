import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class CalendarToolProcessRunner {
    protected static final Path PROJECT_DIRECTORY = Path.of("").toAbsolutePath().normalize();
    private static final Set<String> MISE_MANAGED_COMMANDS =
            Set.of("actionlint", "node", "npm", "shellcheck", "trivy");

    protected static void runCommand(String description, String... command)
            throws IOException, InterruptedException {
        int exitCode = runCommandForExitCode(true, command);
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    protected static void runCommandWithEnvironment(
            String description,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        int exitCode = runCommandForExitCode(true, environment, command);
        if (exitCode != 0) {
            throw new IllegalStateException(description + " failed with exit code " + exitCode + ".");
        }
    }

    protected static void runCommandToFile(
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

    protected static void runCommandWithInput(
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

    protected static String runCommandAndCapture(String description, String... command)
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

    protected static CapturedCommandOutput runCommandAndCaptureCombinedOutput(
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectErrorStream(true);
        Process process = startProcess(processBuilder);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CapturedCommandOutput(process.waitFor(), output);
    }

    protected record CapturedCommandOutput(int exitCode, String output) {}

    protected static int runCommandForExitCode(boolean inheritOutput, String... command)
            throws IOException, InterruptedException {
        return runCommandForExitCode(inheritOutput, Map.of(), command);
    }

    protected static int runCommandForExitCode(
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

    protected static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    protected static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
    }

    protected static void buildDockerImage() throws IOException, InterruptedException {
        runCommand(
                "Production image build",
                "docker",
                "compose",
                "--profile",
                "application",
                "build",
                "web");
    }

    private static ProcessBuilder commandProcessBuilder(String... command) {
        return new ProcessBuilder(resolvedCommandLine(command)).directory(PROJECT_DIRECTORY.toFile());
    }

    static List<String> resolvedCommandLine(String... command) {
        if (command.length == 0) {
            throw new IllegalArgumentException("A subprocess command is required.");
        }

        List<String> commandLine = new ArrayList<>(Arrays.asList(command));
        String commandName = commandLine.getFirst().toLowerCase(Locale.ROOT);
        if (MISE_MANAGED_COMMANDS.contains(commandName)) {
            commandLine.addAll(0, List.of("mise", "exec", "--"));
        } else {
            commandLine.set(0, platformExecutableName(commandLine.getFirst()));
        }
        return List.copyOf(commandLine);
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

    private static String platformExecutableName(String commandName) {
        if (commandName.equals("mvnw")) {
            return isWindows() ? "mvnw.cmd" : "./mvnw";
        }
        if (commandName.equals("npm")) {
            return isWindows() ? "npm.cmd" : "npm";
        }
        return commandName;
    }
}
