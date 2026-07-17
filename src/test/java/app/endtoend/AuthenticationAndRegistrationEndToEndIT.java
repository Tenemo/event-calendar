package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class AuthenticationAndRegistrationEndToEndIT extends SharedCalendarEndToEndSupport {
    @Test
    void reauthenticationNoticeRetainsAUsableStatefulSignInForm() throws SQLException {
        String username = "reauthentication-owner-" + uniqueSuffix();
        seedUser(username);

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = browserContext.newPage();
            navigateToBearerLink(page, route("/login?reauthenticationRequired=true"));
            assertBodyContains(page, "Your session is no longer valid. Sign in again.");
            submitSignInAndWaitForUrl(page, username, TEST_PASSWORD, "**/app/calendars");
        }
    }

    @Test
    void authenticationAndCalendarCreationAreIndependent() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "auth-owner-" + uniqueSuffix;
        String firstCalendarName = "Kayaking " + uniqueSuffix;
        String secondCalendarName = "Dinner " + uniqueSuffix;
        seedUser(ownerUsername);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);

            Response anonymousLoginResponse = navigateToBearerLink(page, route("/login"));
            assertAnonymousSessionCookie(anonymousLoginResponse);
            String anonymousSessionIdentifier = requiredSessionCookieValue(browserContext);
            assertEquals("Sign in", page.locator("h1").textContent().trim());
            page.locator("input[id$='username']").fill(ownerUsername);
            page.locator("input[id$='password']").fill(TEST_PASSWORD + "-wrong");
            page.locator("button:has-text('Sign in')").click();
            assertEquals("Sign in", page.locator("h1").textContent().trim());
            assertBodyContains(page, "Sign-in failed.");
            assertEquals(
                    anonymousSessionIdentifier,
                    requiredSessionCookieValue(browserContext),
                    "Failed authentication must retain the anonymous Faces session.");

            signIn(page, ownerUsername, TEST_PASSWORD);
            assertNotEquals(
                    anonymousSessionIdentifier,
                    requiredSessionCookieValue(browserContext),
                    "Successful authentication must rotate the anonymous session identifier.");
            assertRollingSessionCookie(navigateToBearerLink(page, route("/app/calendars")));
            createCalendar(page, firstCalendarName);
            createCalendar(page, secondCalendarName);
            assertBodyContains(page, firstCalendarName);
            assertBodyContains(page, secondCalendarName);
            assertBodyContains(page, "Admin");

            openCalendar(page, firstCalendarName);
            String calendarId = Long.toString(findCalendarId(firstCalendarName));
            assertCanonicalCalendarRoute(page, calendarId);
            assertRollingSessionCookie(navigateToBearerLink(page, page.url()));
            assertBodyContains(page, "Create event");

            signOut(page);
            assertTrue(
                    URI.create(page.url()).getPath().equals("/") || URI.create(page.url()).getPath().isBlank(),
                    () -> "Expected logout to redirect home, but browser URL was "
                            + redactedDiagnosticUrl(page.url()) + ".");
            assertBodyContains(page, "Sign in");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void signedInPasswordChangeValidatesInputAndRevokesEveryOlderSession() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String username = "password-owner-" + uniqueSuffix;
        String newPassword = "Changed password 2026 " + uniqueSuffix;
        String calendarName = "Password session calendar " + uniqueSuffix;
        seedUser(username);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext changingContext = browser.newContext();
                BrowserContext otherSessionContext = browser.newContext();
                BrowserContext canonicalCalendarSessionContext = browser.newContext()) {
            Page changingPage = newPage(changingContext, browserMessages);
            Page otherSessionPage = newPage(otherSessionContext, browserMessages);
            Page canonicalCalendarSessionPage = newPage(canonicalCalendarSessionContext, browserMessages);
            signIn(changingPage, username, TEST_PASSWORD);
            signIn(otherSessionPage, username, TEST_PASSWORD);
            signIn(canonicalCalendarSessionPage, username, TEST_PASSWORD);
            createCalendar(changingPage, calendarName);
            String calendarId = Long.toString(findCalendarId(calendarName));
            String canonicalCalendarLink = calendarLink(calendarId);
            navigateToBearerLink(canonicalCalendarSessionPage, canonicalCalendarLink);
            assertBodyContains(canonicalCalendarSessionPage, "Create event");

            changingPage.locator("a:has-text('Account settings')").click();
            changingPage.waitForURL("**/app/account-settings");
            assertEquals("Account settings", changingPage.locator("h1").textContent().trim());
            assertBodyContains(changingPage, "Signed in as " + username);
            assertBodyContains(changingPage, "invalidates your other signed-in sessions");

            fillPasswordChangeForm(
                    changingPage,
                    "Wrong current password 1",
                    newPassword,
                    newPassword);
            changingPage.locator("button:has-text('Change password')").click();
            assertBodyContains(changingPage, "Current password is incorrect.");

            fillPasswordChangeForm(
                    changingPage,
                    TEST_PASSWORD,
                    newPassword,
                    newPassword + " mismatch");
            changingPage.locator("button:has-text('Change password')").click();
            assertBodyContains(changingPage, "New password and confirmation must match.");

            fillPasswordChangeForm(
                    changingPage,
                    TEST_PASSWORD,
                    "lowercase password 2026",
                    "lowercase password 2026");
            changingPage.locator("button:has-text('Change password')").click();
            assertBodyContains(changingPage, "Password must contain at least one uppercase letter.");

            fillPasswordChangeForm(
                    changingPage,
                    TEST_PASSWORD,
                    TEST_PASSWORD,
                    TEST_PASSWORD);
            changingPage.locator("button:has-text('Change password')").click();
            assertBodyContains(changingPage, "New password must be different from the current password.");

            fillPasswordChangeForm(changingPage, TEST_PASSWORD, newPassword, newPassword);
            changingPage.locator("button:has-text('Change password')").click();
            changingPage.waitForURL("**/login?passwordChanged=true");
            assertBodyContains(changingPage, "Your password was changed. Sign in with the new password.");
            assertEquals(1, queryLong("select password_version from app_user where username = ?", username));
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from audit_log where actor_user_id = "
                                    + "(select id from app_user where username = ?) and action = 'password_changed'",
                            username));

            navigateToBearerLink(otherSessionPage, route("/app/calendars"));
            URI staleSessionUri = URI.create(otherSessionPage.url());
            assertEquals("/login", staleSessionUri.getPath(), () -> "Unexpected stale-session URL " + staleSessionUri);
            assertBodyContains(otherSessionPage, "Sign in");
            assertFalse(
                    otherSessionContext.cookies(route("/")).stream()
                            .anyMatch(cookie -> "LtpaToken2".equals(cookie.name)),
                    "A Liberty-rejected SSO cookie must be removed before rendering the stateful sign-in form.");

            navigateToBearerLink(canonicalCalendarSessionPage, canonicalCalendarLink);
            assertTrue(
                    URI.create(canonicalCalendarLink)
                            .getPath()
                            .equals(URI.create(canonicalCalendarSessionPage.url()).getPath()),
                    "The revoked authenticated session must remain on the canonical calendar link.");
            assertBodyContains(canonicalCalendarSessionPage, calendarName);
            assertBodyContains(canonicalCalendarSessionPage, "Read-only");
            assertFalse(canonicalCalendarSessionPage.locator("body").innerText().contains("Create event"));

            changingPage.locator("input[id$='username']").fill(username);
            changingPage.locator("input[id$='password']").fill(TEST_PASSWORD);
            changingPage.locator("button:has-text('Sign in')").click();
            assertBodyContains(changingPage, "Sign-in failed.");

            signIn(changingPage, username, newPassword);
            submitSignInAndWaitForUrl(
                    otherSessionPage,
                    username,
                    newPassword,
                    "**/app/calendars");
            assertEquals("/app/calendars", URI.create(changingPage.url()).getPath());
            assertEquals("/app/calendars", URI.create(otherSessionPage.url()).getPath());
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void registrationValidationInactiveAccountsAndLogoutSessionBoundariesAreEnforced() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "registration-owner-" + uniqueSuffix;
        String duplicateUsername = "duplicate-user-" + uniqueSuffix;
        String inactiveUsername = "inactive-user-" + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(duplicateUsername);
        seedUser(inactiveUsername, "Inactive user", false);
        List<String> invitationLinks = new ArrayList<>();

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            for (int invitationIndex = 0; invitationIndex < 4; invitationIndex++) {
                invitationLinks.add(createRegistrationInvitation(ownerPage));
            }
        }

        try (BrowserContext registrationContext = browser.newContext()) {
            Page registrationPage = registrationContext.newPage();
            navigateToBearerLink(registrationPage, invitationLinks.get(0));
            assertBodyContains(
                    registrationPage,
                    "Use at least 8 characters, including one uppercase letter and one digit.");
            fillRegistrationForm(
                    registrationPage,
                    "weak-user-" + uniqueSuffix,
                    "Weak password user",
                    "Weak password calendar",
                    "Short1");
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Password must be at least 8 characters.");

            Locator passwordInput = registrationPage.locator("input[id$='password']");
            passwordInput.fill("lowercase9");
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Password must contain at least one uppercase letter.");

            passwordInput.fill("NoDigitsHere");
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Password must contain at least one digit.");

            String matchingUsername = "matching-password-" + uniqueSuffix;
            navigateToBearerLink(registrationPage, invitationLinks.get(1));
            fillRegistrationForm(
                    registrationPage,
                    matchingUsername,
                    "Matching password user",
                    "Matching password calendar",
                    matchingUsername);
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Password must not match the username.");

            navigateToBearerLink(registrationPage, invitationLinks.get(2));
            fillRegistrationForm(
                    registrationPage,
                    duplicateUsername.toUpperCase(Locale.ROOT),
                    "Duplicate user",
                    "Duplicate calendar",
                    "Valid-duplicate-password-1-" + uniqueSuffix);
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Username is already registered.");

            navigateToBearerLink(registrationPage, invitationLinks.get(3));
            passwordInput = registrationPage.locator("input[id$='password']");
            assertEquals("512", passwordInput.getAttribute("maxlength"));
            passwordInput.fill("p".repeat(513));
            assertEquals(512, passwordInput.inputValue().length());
            fillRegistrationForm(
                    registrationPage,
                    "oversized-password-" + uniqueSuffix,
                    "Oversized password user",
                    "Oversized password calendar",
                    "Valid-placeholder-password-1");
            setRawInputValue(passwordInput, "p".repeat(513));
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Password must be 512 characters or fewer.");
        }

        try (BrowserContext inactiveContext = browser.newContext()) {
            Page inactivePage = inactiveContext.newPage();
            navigateToBearerLink(inactivePage, route("/login"));
            inactivePage.locator("input[id$='username']").fill(inactiveUsername);
            inactivePage.locator("input[id$='password']").fill(TEST_PASSWORD);
            inactivePage.locator("button:has-text('Sign in')").click();
            assertBodyContains(inactivePage, "Sign-in failed.");
        }

        try (BrowserContext sessionContext = browser.newContext()) {
            Page sessionPage = sessionContext.newPage();
            signIn(sessionPage, ownerUsername, TEST_PASSWORD);
            assertEquals("/app/calendars", URI.create(sessionPage.url()).getPath());
            signOut(sessionPage);
            navigateToBearerLink(sessionPage, route("/app/calendars"));
            assertEquals("/login", URI.create(sessionPage.url()).getPath());
            assertBodyContains(sessionPage, "Sign in");
        }
    }

}
