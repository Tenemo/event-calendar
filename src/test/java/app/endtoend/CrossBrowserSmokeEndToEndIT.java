package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CrossBrowserSmokeEndToEndIT {
    private static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9080";
    private static final String TEST_PASSWORD = "correct horse battery staple";
    private static final String SEEDED_PASSWORD_HASH_FOR_TEST_PASSWORD =
            "PBKDF2WithHmacSHA256:600000:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=:"
                    + "YTpMNBE5TiT//mxRmUMHckVy5XS82Y6oz0V8ZImb+/4=";
    private static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(30);

    private final BearerSecretRedactor bearerSecretRedactor = new BearerSecretRedactor();
    private final EndToEndBrowserDiagnostics browserDiagnostics =
            new EndToEndBrowserDiagnostics(CrossBrowserSmokeEndToEndIT.class);
    private URI applicationBaseUri;
    private Playwright playwright;
    private EndToEndBrowserDiagnostics.RecordedBrowser browser;

    @BeforeAll
    void openBrowser() throws InterruptedException {
        applicationBaseUri = URI.create(removeTrailingSlashes(firstNonBlank(
                System.getProperty("app.baseUrl"),
                System.getenv("APP_BASE_URL"),
                DEFAULT_APPLICATION_BASE_URL)));
        waitForApplicationHealth();
        playwright = Playwright.create();
        browser = browserDiagnostics.launch(selectedBrowser(playwright), shouldRunHeadless());
    }

    @BeforeEach
    void prepareBrowserDiagnostics(TestInfo testInfo) {
        browserDiagnostics.startTest(testInfo.getTestMethod()
                .map(java.lang.reflect.Method::getName)
                .orElse(testInfo.getDisplayName()));
    }

    @AfterAll
    void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void authenticationCalendarEventAndPublicLinkWorkInEverySupportedBrowser() throws SQLException {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "browser-smoke-" + uniqueSuffix;
        String calendarName = "Browser plans " + uniqueSuffix;
        String eventTitle = "Browser event " + uniqueSuffix;
        seedUser(username);
        List<String> browserMessages = new ArrayList<>();
        String publicCalendarLink;

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            page.navigate(route("/"));
            assertThat(page.locator("h1")).isVisible();
            waitForPrimeFacesIdle(page);
            assertFalse(hasHorizontalOverflow(page), "The home page must not overflow horizontally.");

            signIn(page, username);
            assertEquals("/app/calendars", URI.create(page.url()).getPath());
            page.locator("input[id$='calendarName']").fill(calendarName);
            page.locator("button:has-text('Create calendar')").click();
            assertBodyContains(page, calendarName);

            page.locator("a", new Page.LocatorOptions().setHasText(calendarName)).first().click();
            waitForCanonicalCalendarRoute(page);
            publicCalendarLink = page.url();
            bearerSecretRedactor.rememberBearerValue(publicCalendarLink);
            page.locator("input[id$='eventTitle']").fill(eventTitle);
            page.locator("input[id$='eventLocation']").fill("Cross-engine location");
            page.locator("input[id$='eventStart_input']").fill("2027-03-20 10:00");
            page.locator("input[id$='eventEnd_input']").fill("2027-03-20 11:30");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, eventTitle);
            assertBodyContains(page, "Cross-engine location");
            assertFalse(hasHorizontalOverflow(page), "The calendar page must not overflow horizontally.");

            page.locator("input[value='Sign out']").click();
            waitForUrlOrFail(page, route("/"), "cross-browser sign-out completion");
            assertThat(page.locator("header nav a:has-text('Sign in')")).isVisible();
        }

        try (BrowserContext anonymousBrowserContext = browser.newContext()) {
            Page anonymousPage = newPage(anonymousBrowserContext, browserMessages);
            navigateToBearerLink(anonymousPage, publicCalendarLink);
            assertBodyContains(anonymousPage, calendarName);
            assertBodyContains(anonymousPage, eventTitle);
            assertBodyContains(anonymousPage, "Read-only");
            assertEquals(0, anonymousPage.locator("button:has-text('Create event')").count());
            assertFalse(
                    hasHorizontalOverflow(anonymousPage),
                    "The public calendar must not overflow horizontally.");
        }

        assertTrue(
                browserMessages.isEmpty(),
                () -> "Expected no browser errors or warnings, but saw: " + browserMessages);
    }

    private Page newPage(BrowserContext browserContext, List<String> browserMessages) {
        Page page = browserContext.newPage();
        page.onConsoleMessage(message -> {
            if (message.type().equals("error") || message.type().equals("warning")) {
                browserMessages.add(message.type() + ": " + bearerSecretRedactor.redact(message.text()));
            }
        });
        page.onPageError(error -> browserMessages.add(
                "page error: " + bearerSecretRedactor.redact(String.valueOf(error))));
        page.onRequestFailed(request -> {
            if (!request.resourceType().equals("document")) {
                browserMessages.add("failed " + request.method() + " " + request.resourceType()
                        + " request for " + redactedDiagnosticUrl(request.url()) + " ("
                        + bearerSecretRedactor.redact(request.failure()) + ") while the page was at "
                        + redactedDiagnosticUrl(page.url()));
            }
        });
        page.onResponse(response -> {
            if (response.status() >= 400 && !response.request().resourceType().equals("document")) {
                browserMessages.add("HTTP " + response.status() + " for "
                        + response.request().resourceType() + " resource");
            }
        });
        return page;
    }

    private void signIn(Page page, String username) {
        page.navigate(route("/login"));
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='password']").fill(TEST_PASSWORD);
        waitForPrimeFacesIdle(page);
        page.locator("button:has-text('Sign in')").click();
        waitForUrlOrFail(page, "**/app/calendars", "cross-browser registration completion");
        waitForPrimeFacesIdle(page);
    }

    private void assertBodyContains(Page page, String expectedText) {
        try {
            page.waitForFunction(
                    "expectedText => document.body && document.body.innerText.includes(expectedText)",
                    expectedText,
                    new Page.WaitForFunctionOptions().setTimeout(5_000));
        } catch (RuntimeException exception) {
            String bodyText;
            try {
                bodyText = page.locator("body").innerText();
            } catch (RuntimeException bodyReadFailure) {
                bodyText = "[body unavailable: " + bodyReadFailure.getClass().getSimpleName() + "]";
            }
            fail("Expected the page body to contain '"
                    + bearerSecretRedactor.redact(expectedText)
                    + "' at "
                    + redactedDiagnosticUrl(page.url())
                    + ", but body was: "
                    + abbreviate(bearerSecretRedactor.redact(bodyText)));
        }
        waitForPrimeFacesIdle(page);
    }

    private void waitForPrimeFacesIdle(Page page) {
        page.waitForFunction(
                "() => typeof PrimeFaces !== 'undefined' && (!PrimeFaces.ajax "
                        + "|| (PrimeFaces.ajax.Queue.isEmpty() && PrimeFaces.ajax.Queue.xhrs.length === 0)) "
                        + "&& (!document.fonts || document.fonts.status === 'loaded')");
    }

    private void waitForUrlOrFail(
            Page page,
            String expectedUrlPattern,
            String actionDescription) {
        try {
            page.waitForURL(expectedUrlPattern);
        } catch (RuntimeException exception) {
            fail("Browser navigation for "
                    + bearerSecretRedactor.redact(actionDescription)
                    + " did not reach "
                    + bearerSecretRedactor.redact(expectedUrlPattern)
                    + "; the browser remained at "
                    + redactedDiagnosticUrl(page.url())
                    + " ("
                    + exception.getClass().getSimpleName()
                    + ").");
        }
    }

    private void waitForCanonicalCalendarRoute(Page page) {
        page.waitForFunction("() => /^\\/[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$/.test(location.pathname)");
    }

    private void navigateToBearerLink(Page page, String bearerLink) {
        try {
            page.navigate(bearerLink);
        } catch (RuntimeException exception) {
            fail("Navigation to " + redactedDiagnosticUrl(bearerLink) + " failed without exposing its bearer value.");
        }
    }

    private boolean hasHorizontalOverflow(Page page) {
        return Boolean.TRUE.equals(page.evaluate(
                "() => document.documentElement.scrollWidth > document.documentElement.clientWidth"));
    }

    private void seedUser(String username) throws SQLException {
        try (Connection connection = databaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into app_user "
                                + "(username, display_name, password_hash, active, created_at, updated_at) "
                                + "values (?, ?, ?, true, now(), now())")) {
            statement.setString(1, username);
            statement.setString(2, "Cross-browser user");
            statement.setString(3, SEEDED_PASSWORD_HASH_FOR_TEST_PASSWORD);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private Connection databaseConnection() throws SQLException {
        String jdbcUrl = "jdbc:postgresql://"
                + firstNonBlank(System.getenv("PGHOST"), "localhost")
                + ":"
                + firstNonBlank(System.getenv("PGPORT"), "5432")
                + "/"
                + firstNonBlank(System.getenv("PGDATABASE"), "calendar");
        return DriverManager.getConnection(
                jdbcUrl,
                firstNonBlank(System.getenv("PGUSER"), "calendar"),
                firstNonBlank(System.getenv("PGPASSWORD"), "calendar"));
    }

    private BrowserType selectedBrowser(Playwright playwright) {
        String browserName = firstNonBlank(System.getenv("BROWSER"), "chromium").toLowerCase(Locale.ROOT);
        return switch (browserName) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> fail("Unsupported Playwright browser '" + browserName
                    + "'. Use chromium, firefox, or webkit.");
        };
    }

    private boolean shouldRunHeadless() {
        if (Boolean.parseBoolean(firstNonBlank(System.getenv("PLAYWRIGHT_HEADED"), "false"))) {
            return false;
        }
        return Boolean.parseBoolean(firstNonBlank(System.getenv("PLAYWRIGHT_HEADLESS"), "true"));
    }

    private void waitForApplicationHealth() throws InterruptedException {
        String configuredHealthUrl = System.getenv("E2E_VERIFICATION_HEALTH_URL");
        URI healthUri = configuredHealthUrl == null || configuredHealthUrl.isBlank()
                ? URI.create(route("/health"))
                : URI.create(configuredHealthUrl.trim());
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadlineNanos = System.nanoTime() + APPLICATION_READY_TIMEOUT.toNanos();
        String lastResult = "no response";
        while (System.nanoTime() < deadlineNanos) {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().trim().equals("ok")) {
                    return;
                }
                lastResult = "HTTP " + response.statusCode() + " with body '" + response.body().trim() + "'";
            } catch (IOException exception) {
                lastResult = exception.getClass().getSimpleName() + ": "
                        + bearerSecretRedactor.redact(exception.getMessage());
            }
            Thread.sleep(250);
        }
        fail("The application did not become healthy at " + healthUri + " within "
                + APPLICATION_READY_TIMEOUT.toSeconds() + " seconds. Last result: " + lastResult + ".");
    }

    private String route(String path) {
        return removeTrailingSlashes(applicationBaseUri.toString()) + path;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("At least one non-blank value is required.");
    }

    private String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }

    private String redactedDiagnosticUrl(String value) {
        return bearerSecretRedactor.redactUrl(value);
    }

    private String abbreviate(String value) {
        String normalizedValue = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalizedValue.length() <= 240 ? normalizedValue : normalizedValue.substring(0, 240) + "...";
    }
}
