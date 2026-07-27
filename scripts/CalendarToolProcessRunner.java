import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class CalendarToolProcessRunner {
    protected static final Path PROJECT_DIRECTORY = Path.of("").toAbsolutePath().normalize();
    private static final Duration GRACEFUL_PROCESS_TERMINATION_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FORCED_PROCESS_TERMINATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration OUTPUT_READER_TERMINATION_TIMEOUT = Duration.ofSeconds(2);
    private static final long PROCESS_TREE_DISCOVERY_INTERVAL_MILLISECONDS = 100;
    private static final long PROCESS_TERMINATION_POLL_INTERVAL_NANOSECONDS =
            Duration.ofMillis(25).toNanos();
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
        return runCommandAndCapture(description, Map.of(), command);
    }

    protected static String runCommandAndCapture(
            String description,
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        CapturedCommandOutput commandOutput = startAndCapture(processBuilder);
        if (commandOutput.exitCode() != 0) {
            throw new IllegalStateException(
                    description + " failed with exit code " + commandOutput.exitCode() + ".");
        }
        return commandOutput.output();
    }

    protected static CapturedCommandOutput runCommandAndCaptureCombinedOutput(
            Map<String, String> environment,
            String... command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = commandProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectErrorStream(true);
        return startAndCapture(processBuilder);
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
        String inputDigestBeforeBuild = CalendarToolVerification.imageBuildInputDigest(PROJECT_DIRECTORY);
        runCommand(
                "Production image build",
                "docker",
                "compose",
                "--profile",
                "application",
                "build",
                "web");
        String inputDigestAfterBuild = CalendarToolVerification.imageBuildInputDigest(PROJECT_DIRECTORY);
        CalendarToolVerification.validateStableImageBuildInputs(
                inputDigestBeforeBuild,
                inputDigestAfterBuild);
        CalendarToolVerification.recordCurrentImageBuildState(inputDigestBeforeBuild);
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
        return waitForProcess(startProcess(processBuilder), null);
    }

    private static CapturedCommandOutput startAndCapture(ProcessBuilder processBuilder)
            throws IOException, InterruptedException {
        Process process = startProcess(processBuilder);
        ProcessOutputCapture outputCapture = startOutputCapture(process);
        int exitCode = waitForProcess(process, outputCapture.reader());
        IOException outputFailure = outputCapture.failure().get();
        if (outputFailure != null) {
            throw outputFailure;
        }
        return new CapturedCommandOutput(
                exitCode,
                outputCapture.output().toString(StandardCharsets.UTF_8));
    }

    private static ProcessOutputCapture startOutputCapture(Process process) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread reader = Thread.ofVirtual()
                .name("calendar-tool-output-reader-" + process.pid())
                .start(() -> {
                    try (var processOutput = process.getInputStream()) {
                        processOutput.transferTo(output);
                    } catch (IOException exception) {
                        failure.set(exception);
                    }
                });
        return new ProcessOutputCapture(output, reader, failure);
    }

    private static int waitForProcess(Process process, Thread outputReader) throws InterruptedException {
        TrackedProcessTree processTree = new TrackedProcessTree(process.toHandle());
        try {
            while (!process.waitFor(
                    PROCESS_TREE_DISCOVERY_INTERVAL_MILLISECONDS,
                    TimeUnit.MILLISECONDS)) {
                processTree.discoverDescendants();
            }
            processTree.discoverDescendants();
            while (outputReader != null && outputReader.isAlive()) {
                processTree.discoverDescendants();
                outputReader.join(PROCESS_TREE_DISCOVERY_INTERVAL_MILLISECONDS);
            }
            return process.exitValue();
        } catch (InterruptedException interruption) {
            throw interruptionAfterTerminatingProcessTree(
                    process,
                    processTree,
                    outputReader,
                    interruption,
                    GRACEFUL_PROCESS_TERMINATION_TIMEOUT,
                    FORCED_PROCESS_TERMINATION_TIMEOUT);
        }
    }

    static InterruptedException interruptionAfterTerminatingProcessTree(
            ProcessHandle rootProcess,
            InterruptedException interruption,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout) {
        return interruptionAfterTerminatingProcessTrees(
                List.of(rootProcess),
                interruption,
                gracefulTerminationTimeout,
                forcedTerminationTimeout);
    }

    static InterruptedException interruptionAfterTerminatingProcessTrees(
            List<? extends ProcessHandle> rootProcesses,
            InterruptedException interruption,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout) {
        return interruptionAfterTerminatingProcessTree(
                null,
                new TrackedProcessTree(rootProcesses),
                null,
                interruption,
                gracefulTerminationTimeout,
                forcedTerminationTimeout);
    }

    protected static void terminateStartedProcessTreesAfterFailure(
            List<Process> startedProcesses,
            Throwable primaryFailure) {
        List<ProcessHandle> rootProcesses = startedProcesses.stream()
                .map(Process::toHandle)
                .toList();
        terminateProcessTreesAfterFailure(
                rootProcesses,
                primaryFailure,
                GRACEFUL_PROCESS_TERMINATION_TIMEOUT,
                FORCED_PROCESS_TERMINATION_TIMEOUT);
    }

    static void terminateProcessTreesAfterFailure(
            List<? extends ProcessHandle> rootProcesses,
            Throwable primaryFailure,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout) {
        terminateProcessTreesAfterFailure(
                rootProcesses,
                primaryFailure,
                gracefulTerminationTimeout,
                forcedTerminationTimeout,
                TimeUnit.NANOSECONDS::sleep);
    }

    static void terminateProcessTreesAfterFailure(
            List<? extends ProcessHandle> rootProcesses,
            Throwable primaryFailure,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout,
            ProcessTerminationWaiter processTerminationWaiter) {
        boolean interruptionWasPending = Thread.interrupted();
        TrackedProcessTree processTree = null;
        try {
            processTree = new TrackedProcessTree(rootProcesses);
            terminateProcessTree(
                    processTree,
                    gracefulTerminationTimeout,
                    forcedTerminationTimeout,
                    processTerminationWaiter);
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        } finally {
            boolean interruptionOccurredDuringCleanup =
                    processTree != null && processTree.interruptionObservedDuringCleanup();
            if (interruptionWasPending
                    || interruptionOccurredDuringCleanup
                    || primaryFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static InterruptedException interruptionAfterTerminatingProcessTree(
            Process process,
            TrackedProcessTree processTree,
            Thread outputReader,
            InterruptedException interruption,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout) {
        try {
            terminateProcessTree(
                    processTree,
                    gracefulTerminationTimeout,
                    forcedTerminationTimeout);
        } catch (RuntimeException cleanupFailure) {
            interruption.addSuppressed(cleanupFailure);
        }

        if (outputReader != null && outputReader.isAlive()) {
            if (!waitForThreadTermination(outputReader, OUTPUT_READER_TERMINATION_TIMEOUT)
                    && process != null) {
                try {
                    process.getInputStream().close();
                } catch (IOException cleanupFailure) {
                    interruption.addSuppressed(cleanupFailure);
                }
                if (!waitForThreadTermination(outputReader, OUTPUT_READER_TERMINATION_TIMEOUT)) {
                    interruption.addSuppressed(new IllegalStateException(
                            "The subprocess output reader did not terminate after the process tree was stopped."));
                }
            }
        }

        Thread.currentThread().interrupt();
        return interruption;
    }

    private static void terminateProcessTree(
            TrackedProcessTree processTree,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout) {
        terminateProcessTree(
                processTree,
                gracefulTerminationTimeout,
                forcedTerminationTimeout,
                TimeUnit.NANOSECONDS::sleep);
    }

    private static void terminateProcessTree(
            TrackedProcessTree processTree,
            Duration gracefulTerminationTimeout,
            Duration forcedTerminationTimeout,
            ProcessTerminationWaiter processTerminationWaiter) {
        requireNonNegativeDuration(gracefulTerminationTimeout, "Graceful process termination timeout");
        requireNonNegativeDuration(forcedTerminationTimeout, "Forced process termination timeout");

        if (requestTerminationAndWait(
                processTree,
                false,
                gracefulTerminationTimeout,
                processTerminationWaiter)) {
            return;
        }
        if (requestTerminationAndWait(
                processTree,
                true,
                forcedTerminationTimeout,
                processTerminationWaiter)) {
            return;
        }

        String remainingProcessIdentifiers = processTree.aliveProcesses().stream()
                .map(processHandle -> Long.toString(processHandle.pid()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("unknown");
        IllegalStateException terminationFailure = new IllegalStateException(
                "Subprocesses remained alive after forced termination: "
                        + remainingProcessIdentifiers
                        + ".");
        processTree.terminationRequestFailures().forEach(terminationFailure::addSuppressed);
        throw terminationFailure;
    }

    private static boolean requestTerminationAndWait(
            TrackedProcessTree processTree,
            boolean forceTermination,
            Duration timeout,
            ProcessTerminationWaiter processTerminationWaiter) {
        long timeoutNanoseconds = durationInNanoseconds(timeout);
        long startTime = System.nanoTime();
        Set<Long> requestedProcessIdentifiers = new HashSet<>();

        while (true) {
            processTree.discoverDescendants();
            for (ProcessHandle processHandle : processTree.aliveProcesses()) {
                if (requestedProcessIdentifiers.add(processHandle.pid())) {
                    try {
                        if (forceTermination) {
                            processHandle.destroyForcibly();
                        } else {
                            processHandle.destroy();
                        }
                    } catch (RuntimeException terminationRequestFailure) {
                        processTree.recordTerminationRequestFailure(terminationRequestFailure);
                    }
                }
            }

            if (processTree.aliveProcesses().isEmpty()) {
                return true;
            }
            long elapsedNanoseconds = System.nanoTime() - startTime;
            if (elapsedNanoseconds >= timeoutNanoseconds) {
                return false;
            }

            long remainingNanoseconds = timeoutNanoseconds - elapsedNanoseconds;
            try {
                processTerminationWaiter.waitFor(Math.min(
                        remainingNanoseconds,
                        PROCESS_TERMINATION_POLL_INTERVAL_NANOSECONDS));
            } catch (InterruptedException ignored) {
                processTree.recordInterruptionDuringCleanup();
            }
        }
    }

    private static boolean waitForThreadTermination(Thread thread, Duration timeout) {
        long timeoutNanoseconds = durationInNanoseconds(timeout);
        long startTime = System.nanoTime();
        while (thread.isAlive()) {
            long elapsedNanoseconds = System.nanoTime() - startTime;
            if (elapsedNanoseconds >= timeoutNanoseconds) {
                return false;
            }
            long remainingNanoseconds = timeoutNanoseconds - elapsedNanoseconds;
            long waitMilliseconds = Math.max(
                    1,
                    Math.min(
                            TimeUnit.NANOSECONDS.toMillis(remainingNanoseconds),
                            PROCESS_TREE_DISCOVERY_INTERVAL_MILLISECONDS));
            try {
                thread.join(waitMilliseconds);
            } catch (InterruptedException ignored) {
                // Cleanup remains uninterruptible; the original status is restored before returning.
            }
        }
        return true;
    }

    private static void requireNonNegativeDuration(Duration duration, String description) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(description + " cannot be negative.");
        }
    }

    private static long durationInNanoseconds(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
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

    private record ProcessOutputCapture(
            ByteArrayOutputStream output,
            Thread reader,
            AtomicReference<IOException> failure) {}

    @FunctionalInterface
    interface ProcessTerminationWaiter {
        void waitFor(long timeoutNanoseconds) throws InterruptedException;
    }

    private static final class TrackedProcessTree {
        private final Map<Long, ProcessHandle> processesByIdentifier = new LinkedHashMap<>();
        private final List<RuntimeException> terminationRequestFailures = new ArrayList<>();
        private boolean interruptionObservedDuringCleanup;

        private TrackedProcessTree(ProcessHandle rootProcess) {
            this(List.of(rootProcess));
        }

        private TrackedProcessTree(List<? extends ProcessHandle> rootProcesses) {
            for (ProcessHandle rootProcess : rootProcesses) {
                processesByIdentifier.putIfAbsent(rootProcess.pid(), rootProcess);
            }
            discoverDescendants();
        }

        private void discoverDescendants() {
            for (ProcessHandle knownProcess : List.copyOf(processesByIdentifier.values())) {
                if (knownProcess.isAlive()) {
                    knownProcess.descendants().forEach(descendant ->
                            processesByIdentifier.putIfAbsent(descendant.pid(), descendant));
                }
            }
        }

        private List<ProcessHandle> aliveProcesses() {
            return processesByIdentifier.values().stream()
                    .filter(ProcessHandle::isAlive)
                    .toList();
        }

        private void recordTerminationRequestFailure(RuntimeException terminationRequestFailure) {
            terminationRequestFailures.add(terminationRequestFailure);
        }

        private List<RuntimeException> terminationRequestFailures() {
            return List.copyOf(terminationRequestFailures);
        }

        private void recordInterruptionDuringCleanup() {
            interruptionObservedDuringCleanup = true;
        }

        private boolean interruptionObservedDuringCleanup() {
            return interruptionObservedDuringCleanup;
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
