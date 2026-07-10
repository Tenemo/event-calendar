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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class SharedCalendarEndToEndIT {
    private static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9080";
    private static final String APPLICATION_BASE_URL_PROPERTY = "app.baseUrl";
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    private static final String BROWSER_ENVIRONMENT_VARIABLE = "BROWSER";
    private static final String POSTGRESQL_HOST_ENVIRONMENT_VARIABLE = "PGHOST";
    private static final String POSTGRESQL_PORT_ENVIRONMENT_VARIABLE = "PGPORT";
    private static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    private static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    private static final String POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE = "PGPASSWORD";
    private static final String PLAYWRIGHT_HEADED_ENVIRONMENT_VARIABLE = "PLAYWRIGHT_HEADED";
    private static final String PLAYWRIGHT_HEADLESS_ENVIRONMENT_VARIABLE = "PLAYWRIGHT_HEADLESS";
    private static final String SEEDED_PASSWORD_HASH_FOR_TEST_PASSWORD =
            "PBKDF2WithHmacSHA256:600000:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=:"
                    + "YTpMNBE5TiT//mxRmUMHckVy5XS82Y6oz0V8ZImb+/4=";
    private static final String TEST_PASSWORD = "correct horse battery staple";
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
            assertBodyContains(page, "Sign in");
            assertBodyContains(page, "Create an account to start planning.");
            assertFalse(hasHorizontalOverflow(page), "The home page should not horizontally overflow at desktop width.");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void invalidPublicCalendarLinkReturnsGenericNoindexNotFoundPage() {
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            com.microsoft.playwright.Response response = page.navigate(route("/calendar/not-a-valid-calendar-token"));

            assertEquals(404, response.status());
            assertEquals("Calendar not found - Shared calendar", page.title());
            assertEquals("Calendar not found", page.locator("h1").textContent().trim());
            assertEquals("noindex, nofollow", page.locator("meta[name='robots']").getAttribute("content"));
            assertBodyContains(page, "invalid or no longer available");
            assertFalse(hasHorizontalOverflow(page), "The public calendar page should not horizontally overflow.");
            assertOnlyExpectedNotFoundNavigationMessage(browserMessages);
        }
    }

    @Test
    void signedInUserCanGenerateAppAndEditorInvitationsForNewUsers() throws SQLException {
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
            String ownerUsername = "owner-e2e-" + uniqueSuffix;
            String appOnlyUsername = "app-e2e-" + uniqueSuffix;
            String editorUsername = "editor-e2e-" + uniqueSuffix;
            String password = "long-enough-password-" + uniqueSuffix;
            String ownerCalendarName = "Kayaking " + uniqueSuffix;
            String appOnlyCalendarName = "Birthday " + uniqueSuffix;
            String editorOwnCalendarName = "Trip " + uniqueSuffix;
            String editorSecondCalendarName = "Dinner " + uniqueSuffix;
            seedUser(ownerUsername);

            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(ownerUsername);
            page.locator("input[id$='password']").fill(TEST_PASSWORD);
            page.locator("button:has-text('Sign in')").click();
            page.waitForURL("**/app/calendars");
            assertBodyContains(page, "App invitations");

            page.locator("input[id$='calendarName']").fill(ownerCalendarName);
            page.locator("button:has-text('Create calendar')").click();
            assertBodyContains(page, ownerCalendarName);

            page.navigate(route("/app/invitations"));
            assertEquals("App invitations - Shared calendar", page.title());
            page.locator("button:has-text('Generate app link')").click();
            Locator generatedInviteLink = page.locator("input[id$='generatedInviteLink']");
            assertThat(generatedInviteLink).isVisible();
            String appOnlyInviteLink = generatedInviteLink.inputValue();
            assertTrue(appOnlyInviteLink.contains("/register?token="), "Generated app invite link should target registration.");

            String ownerCalendarOptionValue = page.locator("select[id$='calendar'] option", new Page.LocatorOptions().setHasText(ownerCalendarName))
                    .getAttribute("value");
            page.locator("select[id$='calendar']").selectOption(ownerCalendarOptionValue);
            page.locator("button:has-text('Generate editor link')").click();
            String editorInviteLink = generatedInviteLink.inputValue();
            assertTrue(editorInviteLink.contains("/register?token="), "Generated editor invite link should target registration.");
            assertFalse(editorInviteLink.equals(appOnlyInviteLink), "Separate invitations should have separate bearer tokens.");

            page.locator("select[id$='calendar']").selectOption(ownerCalendarOptionValue);
            page.locator("button:has-text('Generate editor link')").click();
            String existingUserInviteLink = generatedInviteLink.inputValue();
            assertFalse(existingUserInviteLink.equals(editorInviteLink), "Each editor invitation should have a separate bearer token.");

            page.locator("input[value='Sign out']").click();

            page.navigate(route("/register"));
            page.locator("input[id$='username']").fill("blocked-" + uniqueSuffix);
            page.locator("input[id$='displayName']").fill("Blocked user " + uniqueSuffix);
            page.locator("input[id$='calendarName']").fill("Blocked calendar " + uniqueSuffix);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Register')").click();
            assertBodyContains(page, "Invitation is invalid or no longer available.");

            page.navigate(appOnlyInviteLink);
            page.locator("input[id$='username']").fill(appOnlyUsername);
            page.locator("input[id$='displayName']").fill("App-only user " + uniqueSuffix);
            page.locator("input[id$='calendarName']").fill(appOnlyCalendarName);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Register')").click();

            page.waitForURL("**/app/calendars");
            assertEquals("My calendars - Shared calendar", page.title());
            assertEquals("My calendars", page.locator("h1").textContent().trim());
            assertBodyContains(page, appOnlyCalendarName);
            assertBodyContains(page, "ADMIN");
            assertFalse(
                    page.locator("body").innerText().contains(ownerCalendarName),
                    "An app-only invitation must not grant access to the inviter's calendar.");

            page.navigate(existingUserInviteLink);
            assertBodyContains(page, "already signed in");
            page.locator("button:has-text('Accept invitation')").click();
            page.waitForURL("**/app/calendar?id=*");
            assertBodyContains(page, ownerCalendarName);

            page.locator("input[value='Sign out']").click();

            page.navigate(editorInviteLink);
            page.locator("input[id$='username']").fill(editorUsername);
            page.locator("input[id$='displayName']").fill("Editor user " + uniqueSuffix);
            page.locator("input[id$='calendarName']").fill(editorOwnCalendarName);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Register')").click();

            page.waitForURL("**/app/calendars");
            assertBodyContains(page, editorOwnCalendarName);
            assertBodyContains(page, ownerCalendarName);
            assertBodyContains(page, "EDITOR");
            assertBodyContains(page, "ADMIN");

            page.locator("input[id$='calendarName']").fill(editorSecondCalendarName);
            page.locator("button:has-text('Create calendar')").click();
            assertBodyContains(page, editorSecondCalendarName);

            page.locator("input[value='Sign out']").click();
            assertTrue(
                    URI.create(page.url()).getPath().equals("/") || URI.create(page.url()).getPath().isBlank(),
                    () -> "Expected logout to redirect home, but browser URL was " + page.url() + ".");
            assertBodyContains(page, "Sign in");

            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(editorUsername);
            page.locator("input[id$='password']").fill(password + "-wrong");
            page.locator("button:has-text('Sign in')").click();
            assertBodyContains(page, "Sign-in failed.");

            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(editorUsername);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Sign in')").click();
            page.waitForURL("**/app/calendars");
            assertBodyContains(page, editorOwnCalendarName);
            assertBodyContains(page, editorSecondCalendarName);
            assertBodyContains(page, ownerCalendarName);

            page.locator("input[value='Sign out']").click();
            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(ownerUsername);
            page.locator("input[id$='password']").fill(TEST_PASSWORD);
            page.locator("button:has-text('Sign in')").click();
            page.waitForURL("**/app/calendars");
            page.locator("a:has-text('" + ownerCalendarName + "')").first().click();
            page.waitForURL("**/app/calendar?id=*");
            assertBodyContains(page, "Create event");

            String eventTitle = "River launch " + uniqueSuffix;
            page.locator("input[id$='eventTitle']").fill(eventTitle);
            page.locator("input[id$='eventLocation']").fill("North landing");
            page.locator("input[id$='eventStart_input']").fill("2026-07-20 10:00");
            page.locator("input[id$='eventEnd_input']").fill("2026-07-20 12:00");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, eventTitle);
            assertBodyContains(page, "North landing");

            page.locator("button:has-text('Edit')").first().click();
            page.locator("input[id$='eventTitle']").fill(eventTitle + " updated");
            page.locator("button:has-text('Save changes')").click();
            assertBodyContains(page, eventTitle + " updated");

            String deletedEventTitle = "Temporary event " + uniqueSuffix;
            page.locator("input[id$='eventTitle']").fill(deletedEventTitle);
            page.locator("input[id$='eventStart_input']").fill("2026-07-21 14:00");
            page.locator("input[id$='eventEnd_input']").fill("2026-07-21 15:00");
            page.locator("button:has-text('Create event')").click();
            Locator deletedEventRow = page.locator("article", new Page.LocatorOptions().setHasText(deletedEventTitle));
            deletedEventRow.locator("button:has-text('Delete')").click();
            page.locator("button:has-text('Yes')").click();
            assertEquals(0, page.locator("article", new Page.LocatorOptions().setHasText(deletedEventTitle)).count());

            page.locator("a:has-text('Settings')").click();
            page.waitForURL("**/app/calendar-settings?id=*");
            String calendarDescription = "Summer river plans " + uniqueSuffix;
            page.locator("textarea[id$='calendarDescription']").fill(calendarDescription);
            page.locator("button:has-text('Save settings')").click();
            assertBodyContains(page, "Calendar settings saved.");
            Locator publicLinkInput = page.locator("input.table-copy-field");
            String publicCalendarLink = publicLinkInput.inputValue();
            assertTrue(
                    URI.create(publicCalendarLink).getPath().startsWith("/calendar/"),
                    "Public calendar links should use the clean token route.");

            try (BrowserContext publicBrowserContext = browser.newContext()) {
                List<String> publicBrowserMessages = new ArrayList<>();
                Page publicPage = newPage(publicBrowserContext, publicBrowserMessages);
                com.microsoft.playwright.Response publicResponse = publicPage.navigate(publicCalendarLink);
                assertEquals(200, publicResponse.status());
                assertEquals("noindex, nofollow", publicPage.locator("meta[name='robots']").getAttribute("content"));
                assertBodyContains(publicPage, eventTitle + " updated");
                assertBodyContains(publicPage, calendarDescription);
                assertEquals(0, publicPage.locator("button:has-text('Edit')").count());
                assertEquals(0, publicPage.locator("button:has-text('Delete')").count());
                assertNoBrowserMessages(publicBrowserMessages);
            }

            page.navigate(route("/app/calendars"));
            page.locator("a:has-text('" + ownerCalendarName + "')").first().click();
            page.locator("a:has-text('Members')").click();
            Locator editorMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(editorUsername));
            assertEquals(1, editorMemberRow.count());
            editorMemberRow.locator("select").selectOption("VIEWER");
            editorMemberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "Member role saved.");

            Locator ownerMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(ownerUsername));
            ownerMemberRow.locator("select").selectOption("EDITOR");
            ownerMemberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "A calendar must keep at least one active admin.");

            page.locator("input[value='Sign out']").click();
            page.navigate(route("/login"));
            page.locator("input[id$='username']").fill(editorUsername);
            page.locator("input[id$='password']").fill(password);
            page.locator("button:has-text('Sign in')").click();
            page.waitForURL("**/app/calendars");
            page.locator("a:has-text('" + ownerCalendarName + "')").first().click();
            assertBodyContains(page, eventTitle + " updated");
            assertEquals(0, page.locator("button:has-text('Create event')").count());
            assertEquals(0, page.locator("button:has-text('Edit')").count());
            assertEquals(0, page.locator("button:has-text('Delete')").count());
            assertEquals(0, page.locator("a:has-text('Settings')").count());
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
            assertBodyContains(page, "Sign in");
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

    private void seedUser(String username) throws SQLException {
        String jdbcUrl = "jdbc:postgresql://"
                + firstNonBlank(System.getenv(POSTGRESQL_HOST_ENVIRONMENT_VARIABLE), "localhost")
                + ":"
                + firstNonBlank(System.getenv(POSTGRESQL_PORT_ENVIRONMENT_VARIABLE), "5432")
                + "/"
                + firstNonBlank(System.getenv(POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE), "calendar");

        try (Connection connection = DriverManager.getConnection(
                jdbcUrl,
                firstNonBlank(System.getenv(POSTGRESQL_USER_ENVIRONMENT_VARIABLE), "calendar"),
                firstNonBlank(System.getenv(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE), "calendar"));
                PreparedStatement statement = connection.prepareStatement(
                        "insert into app_user "
                                + "(username, display_name, password_hash, active, created_at, updated_at) "
                                + "values (?, ?, ?, true, now(), now())")) {
            statement.setString(1, username);
            statement.setString(2, "End-to-end user");
            statement.setString(3, SEEDED_PASSWORD_HASH_FOR_TEST_PASSWORD);
            statement.executeUpdate();
        }
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

    private void assertOnlyExpectedNotFoundNavigationMessage(List<String> browserMessages) {
        assertTrue(
                browserMessages.size() <= 1
                        && browserMessages.stream().allMatch(message -> message.contains("status of 404")),
                () -> "Expected only the browser's failed-navigation message for the intentional 404, but saw: "
                        + browserMessages);
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
