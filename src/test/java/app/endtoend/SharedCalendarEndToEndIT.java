package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class SharedCalendarEndToEndIT {
    private static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9080";
    private static final String APPLICATION_BASE_URL_PROPERTY = "app.baseUrl";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String BROWSER_ENVIRONMENT_VARIABLE = "BROWSER";
    private static final String PLAYWRIGHT_HEADED_ENVIRONMENT_VARIABLE = "PLAYWRIGHT_HEADED";
    private static final String PLAYWRIGHT_HEADLESS_ENVIRONMENT_VARIABLE = "PLAYWRIGHT_HEADLESS";
    private static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration APPLICATION_READY_POLL_INTERVAL = Duration.ofMillis(500);

    private Playwright playwright;
    private Browser browser;
    private URI applicationBaseUri;

    @BeforeAll
    void openBrowser() throws InterruptedException {
        applicationBaseUri = resolveApplicationBaseUri();
        waitForApplicationHealth();

        playwright = Playwright.create();
        browser = selectedBrowser(playwright)
                .launch(new BrowserType.LaunchOptions().setHeadless(shouldRunHeadless()));
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
    void homePageRendersSharedCalendarShellAtDesktopSize() {
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);

            page.navigate(route("/"));

            assertEquals("Shared calendar", page.title());
            assertEquals("Shared event calendars for real plans", page.locator("h1").textContent().trim());
            assertBodyContains(page, "Create a calendar");
            assertBodyContains(page, "View public example");
            assertBodyContains(page, "Events are not available yet.");
            assertFalse(hasHorizontalOverflow(page), "The home page should not horizontally overflow at desktop width.");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void publicCalendarLinkOpensNoindexReadOnlyPage() {
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            Locator publicExampleLink = page.locator("main a:has-text(\"View public example\")");

            page.navigate(route("/"));
            assertEquals(1, publicExampleLink.count(), "The home page should expose one public example link.");
            assertTrue(
                    publicExampleLink.getAttribute("href").endsWith("/public-calendar"),
                    "The public example link should use the extensionless route.");

            publicExampleLink.click();

            assertTrue(
                    URI.create(page.url()).getPath().endsWith("/public-calendar"),
                    () -> "Expected the public calendar page, but browser URL was " + page.url() + ".");
            assertEquals("Public calendar - Shared calendar", page.title());
            assertEquals("Kayaking weekend", page.locator("h1").textContent().trim());
            assertEquals("noindex, nofollow", page.locator("meta[name='robots']").getAttribute("content"));
            assertBodyContains(page, "Public visitors can view events only.");
            assertBodyContains(page, "Public links are long random addresses.");
            assertFalse(hasHorizontalOverflow(page), "The public calendar page should not horizontally overflow.");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void registrationLogoutAndLoginUseRealCalendarData() {
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
            String username = "e2e-" + uniqueSuffix;
            String password = "long-enough-password-" + uniqueSuffix;
            String initialCalendarName = "Kayaking " + uniqueSuffix;
            String secondCalendarName = "Birthday " + uniqueSuffix;

            page.navigate(route("/register"));
            page.locator("input[id$='username']").fill(username);
            page.locator("input[id$='displayName']").fill("End-to-end user " + uniqueSuffix);
            page.locator("input[id$='calendarName']").fill(initialCalendarName);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Register')").click();

            page.waitForURL("**/app/calendars");
            assertEquals("My calendars - Shared calendar", page.title());
            assertEquals("My calendars", page.locator("h1").textContent().trim());
            assertBodyContains(page, initialCalendarName);
            assertBodyContains(page, "ADMIN");

            page.locator("input[id$='calendarName']").fill(secondCalendarName);
            page.locator("button:has-text('Create calendar')").click();
            assertBodyContains(page, secondCalendarName);

            page.locator("input[value='Sign out']").click();
            assertTrue(
                    URI.create(page.url()).getPath().equals("/") || URI.create(page.url()).getPath().isBlank(),
                    () -> "Expected logout to redirect home, but browser URL was " + page.url() + ".");
            assertBodyContains(page, "Sign in");

            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(username);
            page.locator("input[id$='password']").fill(password + "-wrong");
            page.locator("button:has-text('Sign in')").click();
            assertBodyContains(page, "Sign-in failed.");

            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(username);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Sign in')").click();
            page.waitForURL("**/app/calendars");
            assertBodyContains(page, initialCalendarName);
            assertBodyContains(page, secondCalendarName);
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void homePageDoesNotOverflowAtMobileWidth() {
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions().setViewportSize(390, 844))) {
            Page page = newPage(browserContext, browserMessages);

            page.navigate(route("/"));

            assertEquals("Shared event calendars for real plans", page.locator("h1").textContent().trim());
            assertBodyContains(page, "Register");
            assertBodyContains(page, "Kayaking weekend");
            assertFalse(hasHorizontalOverflow(page), "The home page should not horizontally overflow at mobile width.");
            assertNoBrowserMessages(browserMessages);
        }
    }

    private Page newPage(BrowserContext browserContext, List<String> browserMessages) {
        Page page = browserContext.newPage();
        page.onConsoleMessage(message -> {
            String messageType = message.type();
            if (messageType.equals("error") || messageType.equals("warning")) {
                browserMessages.add(messageType + ": " + message.text());
            }
        });
        return page;
    }

    private void waitForApplicationHealth() throws InterruptedException {
        URI healthUri = URI.create(removeTrailingSlashes(applicationBaseUri.toString()) + "/health");
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        long deadlineNanos = System.nanoTime() + APPLICATION_READY_TIMEOUT.toNanos();
        String lastHealthCheckResult = "no response";

        while (System.nanoTime() < deadlineNanos) {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String responseBody = response.body().trim();

                if (response.statusCode() == 200 && responseBody.equals("ok")) {
                    return;
                }

                lastHealthCheckResult = "HTTP " + response.statusCode() + " with body '" + abbreviate(responseBody) + "'";
            } catch (IOException exception) {
                lastHealthCheckResult = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }

            Thread.sleep(APPLICATION_READY_POLL_INTERVAL.toMillis());
        }

        fail("The application did not become healthy at " + healthUri + " within "
                + APPLICATION_READY_TIMEOUT.toSeconds() + " seconds. Last result: "
                + lastHealthCheckResult + ". Start the app with 'mise run dev' before running browser tests.");
    }

    private URI resolveApplicationBaseUri() {
        return URI.create(removeTrailingSlashes(firstNonBlank(
                System.getProperty(APPLICATION_BASE_URL_PROPERTY),
                System.getenv(APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE),
                DEFAULT_APPLICATION_BASE_URL)));
    }

    private BrowserType selectedBrowser(Playwright playwright) {
        String browserName = firstNonBlank(System.getenv(BROWSER_ENVIRONMENT_VARIABLE), "chromium")
                .toLowerCase(Locale.ROOT);

        return switch (browserName) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> fail("Unsupported Playwright browser '" + browserName + "'. Use chromium, firefox, or webkit.");
        };
    }

    private boolean shouldRunHeadless() {
        if (Boolean.parseBoolean(firstNonBlank(System.getenv(PLAYWRIGHT_HEADED_ENVIRONMENT_VARIABLE), "false"))) {
            return false;
        }

        return Boolean.parseBoolean(firstNonBlank(System.getenv(PLAYWRIGHT_HEADLESS_ENVIRONMENT_VARIABLE), "true"));
    }

    private String route(String path) {
        return removeTrailingSlashes(applicationBaseUri.toString()) + path;
    }

    private void assertBodyContains(Page page, String expectedText) {
        try {
            assertThat(page.locator("body"))
                    .containsText(expectedText, new LocatorAssertions.ContainsTextOptions().setTimeout(5_000));
        } catch (AssertionError assertionError) {
            String bodyText = page.locator("body").innerText();
            fail(
                    "Expected page body to contain '" + expectedText + "' at " + page.url()
                            + " with title '" + page.title() + "', but body was: " + abbreviate(bodyText),
                    assertionError);
        }
    }

    private void assertNoBrowserMessages(List<String> browserMessages) {
        assertTrue(
                browserMessages.isEmpty(),
                () -> "Expected no browser console errors or warnings, but saw: " + browserMessages);
    }

    private boolean hasHorizontalOverflow(Page page) {
        Object result = page.evaluate(
                "() => document.documentElement.scrollWidth > document.documentElement.clientWidth");
        return Boolean.TRUE.equals(result);
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

    private String abbreviate(String value) {
        String normalizedValue = value.replaceAll("\\s+", " ").trim();
        if (normalizedValue.length() <= 240) {
            return normalizedValue;
        }

        return normalizedValue.substring(0, 240) + "...";
    }
}
