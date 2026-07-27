package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.security.PasswordService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SameSiteAttribute;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/*
 * This trusted default-branch test deliberately submits reusable, low-sensitivity preview credentials
 * to PR-built application code. Those credentials must never be used outside disposable previews.
 */
final class PreviewDeploymentAuthenticationIT {
    private static final String BASE_URL_ENVIRONMENT_VARIABLE = "PREVIEW_VERIFICATION_BASE_URL";
    private static final String PULL_REQUEST_NUMBER_ENVIRONMENT_VARIABLE =
            "PREVIEW_VERIFICATION_PULL_REQUEST_NUMBER";
    private static final String PASSWORD_ENVIRONMENT_VARIABLE = "PREVIEW_VERIFICATION_PASSWORD";
    private static final String BOOTSTRAP_TOKEN_ENVIRONMENT_VARIABLE =
            "PREVIEW_BOOTSTRAP_INVITE_TOKEN";

    @Test
    void previewSupportsRegistrationAndAuthenticationThroughTheRailwayProxy() {
        String pullRequestNumber = requiredPullRequestNumber();
        URI previewBaseUri = requiredPreviewBaseUri(pullRequestNumber);
        String password = requiredPassword();
        String bootstrapInvitationToken = requiredSecret(BOOTSTRAP_TOKEN_ENVIRONMENT_VARIABLE);
        String username = "preview-pr-" + pullRequestNumber;

        Playwright playwright = Playwright.create();
        Browser browser = null;
        try {
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try (BrowserContext browserContext = browser.newContext()) {
                Page page = browserContext.newPage();
                boolean existingAccountAuthenticated = submitSignIn(
                        page,
                        previewBaseUri,
                        username,
                        password);
                if (!existingAccountAuthenticated) {
                    registerBootstrapAccount(
                            page,
                            previewBaseUri,
                            bootstrapInvitationToken,
                            username,
                            password);
                }

                Response protectedPageResponse = page.navigate(
                        previewBaseUri.resolve("/app/calendars").toString());
                assertNotNull(protectedPageResponse, "The protected preview page did not return a response.");
                assertEquals(200, protectedPageResponse.status());
                assertEquals("/app/calendars", URI.create(page.url()).getPath());
                assertEquals(
                        "noindex, nofollow",
                        protectedPageResponse.headerValue("x-robots-tag"),
                        "Every non-production Railway response must opt out of indexing.");
                assertAuthenticatedCookieContracts(browserContext, previewBaseUri);
            }
        } finally {
            if (browser != null) {
                browser.close();
            }
            playwright.close();
        }
    }

    @Test
    void previewPasswordAdmissionMatchesTheApplicationLengthPolicy() {
        validatePreviewPassword("a".repeat(PasswordService.MINIMUM_PASSWORD_LENGTH));
        assertThrows(
                IllegalStateException.class,
                () -> validatePreviewPassword("a".repeat(PasswordService.MINIMUM_PASSWORD_LENGTH - 1)));
        assertThrows(
                IllegalStateException.class,
                () -> validatePreviewPassword("a".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH + 1)));
    }

    private boolean submitSignIn(
            Page page,
            URI previewBaseUri,
            String username,
            String password) {
        Response signInPageResponse = page.navigate(previewBaseUri.resolve("/login").toString());
        assertNotNull(signInPageResponse, "The preview sign-in page did not return a response.");
        assertEquals(200, signInPageResponse.status());
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='password']").fill(password);
        page.locator("button:has-text('Sign in'), input[type='submit'][value='Sign in']")
                .click();
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);

        String resultingPath = URI.create(page.url()).getPath();
        assertTrue(
                resultingPath.equals("/login") || resultingPath.equals("/app/calendars"),
                "Sign-in must either authenticate the stable preview account or retain the sign-in page.");
        return resultingPath.equals("/app/calendars");
    }

    private void registerBootstrapAccount(
            Page page,
            URI previewBaseUri,
            String bootstrapInvitationToken,
            String username,
            String password) {
        String registrationUrl = previewBaseUri.resolve("/register?token="
                + URLEncoder.encode(bootstrapInvitationToken, StandardCharsets.UTF_8)).toString();
        try {
            Response registrationPageResponse = page.navigate(registrationUrl);
            assertNotNull(registrationPageResponse, "The preview registration page did not return a response.");
            assertEquals(200, registrationPageResponse.status());
        } catch (RuntimeException | AssertionError exception) {
            throw new AssertionError("The preview bootstrap registration page could not be opened.");
        }
        page.evaluate("history.replaceState(null, '', '/register')");
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='displayName']").fill("Preview pull request " + requiredPullRequestNumber());
        page.locator("input[id$='calendarName']").fill("Preview verification");
        page.locator("input[id$='password']").fill(password);
        page.locator("input[id$='passwordConfirmation']").fill(password);
        page.locator("button:has-text('Register')").click();
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        assertEquals(
                "/app/calendars",
                URI.create(page.url()).getPath(),
                "The preview bootstrap account could not be created. Recreate the preview environment if its bootstrap invitation was consumed by another account.");
    }

    private void assertAuthenticatedCookieContracts(
            BrowserContext browserContext,
            URI previewBaseUri) {
        List<Cookie> cookies = browserContext.cookies(previewBaseUri.toString());
        assertSecureCookie(cookies, "LtpaToken2");
        assertSecureCookie(cookies, "JSESSIONID");
    }

    private void assertSecureCookie(List<Cookie> cookies, String cookieName) {
        List<Cookie> matchingCookies = cookies.stream()
                .filter(cookie -> cookieName.equals(cookie.name))
                .toList();
        assertEquals(1, matchingCookies.size(), () -> "Expected exactly one " + cookieName + " cookie.");
        Cookie cookie = matchingCookies.getFirst();
        assertEquals(Boolean.TRUE, cookie.secure, () -> cookieName + " must be Secure.");
        assertEquals(Boolean.TRUE, cookie.httpOnly, () -> cookieName + " must be HTTP-only.");
        assertEquals(SameSiteAttribute.LAX, cookie.sameSite, () -> cookieName + " must use SameSite Lax.");
        assertEquals("/", cookie.path, () -> cookieName + " must apply to the whole application.");
    }

    private URI requiredPreviewBaseUri(String pullRequestNumber) {
        URI previewBaseUri;
        try {
            previewBaseUri = URI.create(requiredSecret(BASE_URL_ENVIRONMENT_VARIABLE));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PREVIEW_VERIFICATION_BASE_URL must be a valid URL.");
        }
        String expectedHostnameSuffix = "-event-calendar-pr-" + pullRequestNumber + ".up.railway.app";
        if (!"https".equalsIgnoreCase(previewBaseUri.getScheme())
                || previewBaseUri.getHost() == null
                || !previewBaseUri.getHost().endsWith(expectedHostnameSuffix)
                || previewBaseUri.getUserInfo() != null
                || previewBaseUri.getPort() != -1
                || !(previewBaseUri.getPath() == null
                        || previewBaseUri.getPath().isEmpty()
                        || previewBaseUri.getPath().equals("/"))
                || previewBaseUri.getQuery() != null
                || previewBaseUri.getFragment() != null) {
            throw new IllegalStateException(
                    "PREVIEW_VERIFICATION_BASE_URL must be the expected Railway HTTPS preview origin.");
        }
        return URI.create(previewBaseUri.getScheme() + "://" + previewBaseUri.getHost());
    }

    private String requiredPullRequestNumber() {
        String pullRequestNumber = requiredSecret(PULL_REQUEST_NUMBER_ENVIRONMENT_VARIABLE);
        if (!pullRequestNumber.matches("[1-9][0-9]*")) {
            throw new IllegalStateException(
                    "PREVIEW_VERIFICATION_PULL_REQUEST_NUMBER must be a positive integer.");
        }
        return pullRequestNumber;
    }

    private String requiredPassword() {
        String password = requiredSecret(PASSWORD_ENVIRONMENT_VARIABLE);
        validatePreviewPassword(password);
        return password;
    }

    private static void validatePreviewPassword(String password) {
        int passwordLength = password.codePointCount(0, password.length());
        if (passwordLength < PasswordService.MINIMUM_PASSWORD_LENGTH
                || passwordLength > PasswordService.MAXIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "PREVIEW_VERIFICATION_PASSWORD must satisfy the application password policy.");
        }
    }

    private String requiredSecret(String variableName) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variableName + " is required.");
        }
        return value;
    }
}
