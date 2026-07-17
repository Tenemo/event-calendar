package app.endtoend;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

final class EndToEndBrowserDiagnostics {
    private static final String ARTIFACTS_DIRECTORY_ENVIRONMENT_VARIABLE = "E2E_ARTIFACTS_DIRECTORY";

    private final String testClassName;
    private Path currentTestDirectory;
    private int browserContextIndex;

    EndToEndBrowserDiagnostics(Class<?> testClass) {
        testClassName = testClass.getSimpleName();
    }

    void startTest(String testName) {
        browserContextIndex = 0;
        String configuredArtifactsDirectory = System.getenv(ARTIFACTS_DIRECTORY_ENVIRONMENT_VARIABLE);
        if (configuredArtifactsDirectory == null || configuredArtifactsDirectory.isBlank()) {
            currentTestDirectory = null;
            return;
        }

        String browserName = environmentValueOrDefault("BROWSER", "chromium");
        currentTestDirectory = Path.of(configuredArtifactsDirectory.trim())
                .resolve(fileNameSegment(browserName))
                .resolve(fileNameSegment(testClassName))
                .resolve(fileNameSegment(testName));
        try {
            clearPreviousTestArtifacts(currentTestDirectory);
            Files.createDirectories(currentTestDirectory);
            Files.writeString(
                    currentTestDirectory.resolve("test-metadata.txt"),
                    "Test class: " + testClassName + System.lineSeparator()
                            + "Test: " + testName + System.lineSeparator()
                            + "Browser: " + browserName + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not prepare end-to-end diagnostic artifacts at " + currentTestDirectory + ".",
                    exception);
        }
    }

    BrowserContext newBrowserContext(Browser browser) {
        return newBrowserContext(browser, new Browser.NewContextOptions());
    }

    BrowserContext newBrowserContext(Browser browser, Browser.NewContextOptions options) {
        String applicationBaseUrl = System.getenv("APP_BASE_URL");
        if (applicationBaseUrl != null
                && !applicationBaseUrl.isBlank()
                && "https".equalsIgnoreCase(URI.create(applicationBaseUrl.trim()).getScheme())) {
            options.setIgnoreHTTPSErrors(true);
        }
        if (currentTestDirectory == null) {
            return browser.newContext(options);
        }

        browserContextIndex++;
        Path browserContextDirectory = currentTestDirectory.resolve(
                "browser-context-" + String.format(Locale.ROOT, "%02d", browserContextIndex));
        try {
            Files.createDirectories(browserContextDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not prepare browser diagnostic directory " + browserContextDirectory + ".",
                    exception);
        }

        // Videos do not include browser chrome, so bearer tokens in the address bar are not recorded.
        // The isolated test database is removed after the suite, but generated-link values are still
        // masked in the page viewport to keep the artifacts free of bearer secrets.
        options.setRecordVideoDir(browserContextDirectory);
        BrowserContext browserContext = browser.newContext(options);
        browserContext.addInitScript(
                "() => {"
                        + "const style = document.createElement('style');"
                        + "style.textContent = \"input[id$='generatedInvitationLink'], .table-copy-field { "
                        + "-webkit-text-security: disc !important; filter: blur(6px) !important; }\";"
                        + "document.documentElement.appendChild(style);"
                        + "}");
        return diagnosticBrowserContext(browserContext, browserContextDirectory);
    }

    RecordedBrowser record(Browser browser) {
        return new RecordedBrowser(browser, this);
    }

    RecordedBrowser launch(BrowserType browserType, boolean headless) {
        BrowserType.LaunchOptions launchOptions =
                new BrowserType.LaunchOptions().setHeadless(headless);
        String browserName = environmentValueOrDefault("BROWSER", "chromium");
        if (usesLoopbackHttps() && browserName.equalsIgnoreCase("chromium")) {
            launchOptions.setArgs(List.of("--ignore-certificate-errors"));
        }
        return record(browserType.launch(launchOptions));
    }

    private String fileNameSegment(String value) {
        String normalizedValue = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalizedValue.isBlank() ? "unnamed" : normalizedValue;
    }

    private String environmentValueOrDefault(String variableName, String defaultValue) {
        String configuredValue = System.getenv(variableName);
        return configuredValue == null || configuredValue.isBlank() ? defaultValue : configuredValue.trim();
    }

    private boolean usesLoopbackHttps() {
        String applicationBaseUrl = System.getenv("APP_BASE_URL");
        if (applicationBaseUrl == null || applicationBaseUrl.isBlank()) {
            return false;
        }
        URI applicationBaseUri = URI.create(applicationBaseUrl.trim());
        String host = applicationBaseUri.getHost();
        return "https".equalsIgnoreCase(applicationBaseUri.getScheme())
                && ("localhost".equalsIgnoreCase(host)
                        || "127.0.0.1".equals(host)
                        || "::1".equals(host));
    }

    private BrowserContext diagnosticBrowserContext(
            BrowserContext browserContext,
            Path browserContextDirectory) {
        AtomicBoolean artifactsFinalized = new AtomicBoolean();
        return (BrowserContext) Proxy.newProxyInstance(
                BrowserContext.class.getClassLoader(),
                new Class<?>[] {BrowserContext.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")
                            && method.getParameterCount() == 0
                            && artifactsFinalized.compareAndSet(false, true)) {
                        try {
                            finalizeArtifacts(browserContext, browserContextDirectory);
                        } catch (Throwable throwable) {
                            writeUnexpectedArtifactError(browserContextDirectory, throwable);
                        }
                    }
                    try {
                        return method.invoke(browserContext, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private void finalizeArtifacts(BrowserContext browserContext, Path browserContextDirectory) {
        StringBuilder artifactErrors = new StringBuilder();
        int pageIndex = 0;
        try {
            for (Page page : browserContext.pages()) {
                pageIndex++;
                try {
                    page.screenshot(new Page.ScreenshotOptions()
                            .setFullPage(true)
                            .setPath(browserContextDirectory.resolve(
                                    "page-" + String.format(Locale.ROOT, "%02d", pageIndex) + ".png")));
                } catch (RuntimeException exception) {
                    appendArtifactError(artifactErrors, "page screenshot " + pageIndex, exception);
                }
            }
        } catch (Throwable throwable) {
            appendArtifactError(artifactErrors, "browser page enumeration", throwable);
        }

        if (!artifactErrors.isEmpty()) {
            try {
                Files.writeString(
                        browserContextDirectory.resolve("artifact-errors.txt"),
                        artifactErrors,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
                // Diagnostics must never replace the test's original result.
            }
        }
    }

    private void appendArtifactError(StringBuilder errors, String operation, Throwable exception) {
        errors.append(operation)
                .append(": ")
                .append(exception.getClass().getSimpleName())
                .append(System.lineSeparator());
    }

    private void clearPreviousTestArtifacts(Path testDirectory) throws IOException {
        if (!Files.exists(testDirectory)) {
            return;
        }
        try (Stream<Path> artifactPaths = Files.walk(testDirectory)) {
            for (Path artifactPath : artifactPaths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(artifactPath);
            }
        }
    }

    private void writeUnexpectedArtifactError(Path browserContextDirectory, Throwable throwable) {
        try {
            Files.writeString(
                    browserContextDirectory.resolve("artifact-errors.txt"),
                    "Unexpected artifact collection failure: "
                            + throwable.getClass().getSimpleName()
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // Diagnostics must never replace the test's original result or block context cleanup.
        }
    }

    static final class RecordedBrowser implements AutoCloseable {
        private final Browser browser;
        private final EndToEndBrowserDiagnostics diagnostics;

        private RecordedBrowser(Browser browser, EndToEndBrowserDiagnostics diagnostics) {
            this.browser = browser;
            this.diagnostics = diagnostics;
        }

        BrowserContext newContext() {
            return diagnostics.newBrowserContext(browser);
        }

        BrowserContext newContext(Browser.NewContextOptions options) {
            return diagnostics.newBrowserContext(browser, options);
        }

        @Override
        public void close() {
            browser.close();
        }
    }
}
