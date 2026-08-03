package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.fixture.CanonicalCalendarFixture;
import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductionVerificationEndToEndIT {
    private URI applicationBaseUri;
    private String username;
    private String password;
    private boolean ignoreHttpsErrors;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void startBrowser() throws Exception {
        applicationBaseUri = requireProductionOrigin(requiredEnvironment("REMOTE_VERIFICATION_BASE_URL"));
        username = requiredEnvironment("REMOTE_VERIFICATION_USERNAME");
        password = requiredEnvironment("REMOTE_VERIFICATION_PASSWORD");
        ignoreHttpsErrors = isLoopbackHost(applicationBaseUri.getHost());

        playwright = Playwright.create();
        verifyHealth();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void stopBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void publicPagesAreHealthyAccessibleAndKeyboardReady() {
        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();

            Response landingResponse = navigate(page, "/");
            assertSuccessfulResponse(landingResponse);
            assertSecurityHeaders(landingResponse);
            assertEquals("calendar.social", page.title());
            assertAccessible(page);

            Response signInResponse = navigate(page, "/sign-in");
            assertSuccessfulResponse(signInResponse);
            assertSecurityHeaders(signInResponse);
            assertEquals("Sign in - calendar.social", page.title());
            assertTrue(
                    Boolean.TRUE.equals(page.locator("input[id$='username']")
                            .evaluate("input => document.activeElement === input")),
                    "The production sign-in username should receive initial focus.");

            page.setViewportSize(390, 844);
            assertNoHorizontalOverflow(page);
            assertAccessible(page);
        }
    }

    @Test
    void persistentVerificationAccountCanReadCanonicalAuthenticatedPages() {
        List<String> pageErrors = new ArrayList<>();
        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.onPageError(pageErrors::add);
            signIn(page);

            assertEquals("/app/calendars", URI.create(page.url()).getPath());
            assertThat(page.locator("body")).containsText(CanonicalCalendarFixture.FRIENDS_CALENDAR_NAME);
            assertThat(page.locator("body")).containsText(CanonicalCalendarFixture.WEEKEND_CALENDAR_NAME);
            assertThat(page.locator("body")).containsText(CanonicalCalendarFixture.LISBON_CALENDAR_NAME);
            assertEquals(3, page.locator(".calendar-card").count());
            assertAccessible(page);

            Response invitationsResponse = navigate(page, "/app/invitations");
            assertSuccessfulResponse(invitationsResponse);
            assertSecurityHeaders(invitationsResponse);
            assertThat(page.locator("h1")).hasText("Invitations");
            assertAccessible(page);

            Response accountSettingsResponse = navigate(page, "/app/account-settings");
            assertSuccessfulResponse(accountSettingsResponse);
            assertSecurityHeaders(accountSettingsResponse);
            assertThat(page.locator("h1")).hasText("Account settings");

            page.setViewportSize(390, 844);
            assertNoHorizontalOverflow(page);
            assertAccessible(page);
            assertTrue(pageErrors.isEmpty(), () -> "Production browser errors: " + pageErrors);
        }
    }

    private BrowserContext newBrowserContext() {
        return browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1440, 1000)
                .setIgnoreHTTPSErrors(ignoreHttpsErrors));
    }

    private void signIn(Page page) {
        navigate(page, "/sign-in");
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='password']").fill(password);
        page.locator("input[type='submit'][value='Sign in']").click();
        page.waitForURL("**/app/calendars");
    }

    private Response navigate(Page page, String path) {
        return page.navigate(applicationBaseUri.resolve(path).toString());
    }

    private void verifyHealth() {
        APIRequest.NewContextOptions requestOptions =
                new APIRequest.NewContextOptions().setIgnoreHTTPSErrors(ignoreHttpsErrors);
        APIRequestContext requestContext =
                playwright.request().newContext(requestOptions);
        try {
            APIResponse healthResponse =
                    requestContext.get(applicationBaseUri.resolve("/health").toString());
            assertEquals(200, healthResponse.status());
            assertEquals("ok", healthResponse.text());
        } finally {
            requestContext.dispose();
        }
    }

    private static URI requireProductionOrigin(String configuredBaseUrl) {
        URI baseUri;
        try {
            baseUri = URI.create(configuredBaseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "REMOTE_VERIFICATION_BASE_URL must be a valid HTTPS origin.", exception);
        }
        if (!"https".equalsIgnoreCase(baseUri.getScheme())
                || baseUri.getHost() == null
                || baseUri.getUserInfo() != null
                || (baseUri.getPath() != null && !baseUri.getPath().isEmpty() && !"/".equals(baseUri.getPath()))
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null) {
            throw new IllegalStateException(
                    "REMOTE_VERIFICATION_BASE_URL must be an HTTPS origin without credentials, a path, a query, or a fragment.");
        }
        return URI.create(baseUri.toString().replaceAll("/+$", "") + "/");
    }

    private static boolean isLoopbackHost(String host) {
        return Set.of("localhost", "127.0.0.1", "::1").contains(host.toLowerCase());
    }

    private static String requiredEnvironment(String variableName) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variableName + " is required for production verification.");
        }
        return value;
    }

    private static void assertSuccessfulResponse(Response response) {
        assertNotNull(response, "Production navigation returned no HTTP response.");
        assertTrue(
                response.ok(),
                () -> "Production navigation returned HTTP " + response.status() + ".");
    }

    private static void assertSecurityHeaders(Response response) {
        assertEquals("DENY", response.headerValue("x-frame-options"));
        assertEquals("nosniff", response.headerValue("x-content-type-options"));
        assertNotNull(response.headerValue("content-security-policy"));
        assertNotNull(response.headerValue("strict-transport-security"));
    }

    private static void assertNoHorizontalOverflow(Page page) {
        assertFalse(
                Boolean.TRUE.equals(page.evaluate(
                        "() => document.documentElement.scrollWidth > document.documentElement.clientWidth")),
                "Production page should not have horizontal overflow at mobile width.");
    }

    private static void assertAccessible(Page page) {
        AxeResults results = new AxeBuilder(page).analyze();
        assertTrue(
                results.getViolations().isEmpty(),
                () -> "Production accessibility violations: "
                        + results.getViolations().stream()
                                .map(violation -> violation.getId()
                                        + ": "
                                        + violation.getNodes().stream()
                                                .map(node -> node.getTarget()
                                                        + " - "
                                                        + node.getFailureSummary())
                                                .toList())
                                .toList());
    }
}
