package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.ReducedMotion;
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
    private static final int[] RESPONSIVE_WIDTHS = {320, 390, 768, 820, 1024, 1280};

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
    void authenticationAndCalendarCreationAreIndependent() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "auth-owner-" + uniqueSuffix;
        String firstCalendarName = "Kayaking " + uniqueSuffix;
        String secondCalendarName = "Dinner " + uniqueSuffix;
        seedUser(ownerUsername);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);

            page.navigate(route("/login"));
            assertEquals("Sign in", page.locator("h1").textContent().trim());
            page.locator("input[id$='username']").fill(ownerUsername);
            page.locator("input[id$='password']").fill(TEST_PASSWORD + "-wrong");
            page.locator("button:has-text('Sign in')").click();
            assertEquals("Sign in", page.locator("h1").textContent().trim());
            assertBodyContains(page, "Sign-in failed.");

            String unauthenticatedSessionIdentifier = requiredSessionCookieValue(browserContext);
            signIn(page, ownerUsername, TEST_PASSWORD);
            assertNotEquals(unauthenticatedSessionIdentifier, requiredSessionCookieValue(browserContext));
            assertRollingSessionCookie(page.navigate(route("/app/calendars")));
            createCalendar(page, firstCalendarName);
            createCalendar(page, secondCalendarName);
            assertBodyContains(page, firstCalendarName);
            assertBodyContains(page, secondCalendarName);
            assertBodyContains(page, "ADMIN");

            openCalendar(page, firstCalendarName);
            String calendarId = Long.toString(findCalendarId(firstCalendarName));
            assertCanonicalCalendarRoute(page, calendarId);
            assertRollingSessionCookie(page.reload());
            assertBodyContains(page, "Create event");

            signOut(page);
            assertTrue(
                    URI.create(page.url()).getPath().equals("/") || URI.create(page.url()).getPath().isBlank(),
                    () -> "Expected logout to redirect home, but browser URL was " + page.url() + ".");
            assertBodyContains(page, "Sign in");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void signedInPasswordChangeValidatesInputAndRevokesEveryOlderSession() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String username = "password-owner-" + uniqueSuffix;
        String newPassword = "Changed password 2026 " + uniqueSuffix;
        seedUser(username);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext changingContext = browser.newContext();
                BrowserContext otherSessionContext = browser.newContext()) {
            Page changingPage = newPage(changingContext, browserMessages);
            Page otherSessionPage = newPage(otherSessionContext, browserMessages);
            signIn(changingPage, username, TEST_PASSWORD);
            signIn(otherSessionPage, username, TEST_PASSWORD);

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

            otherSessionPage.navigate(route("/app/calendars"));
            URI staleSessionUri = URI.create(otherSessionPage.url());
            assertEquals("/login", staleSessionUri.getPath(), () -> "Unexpected stale-session URL " + staleSessionUri);
            assertBodyContains(otherSessionPage, "Sign in");

            changingPage.locator("input[id$='username']").fill(username);
            changingPage.locator("input[id$='password']").fill(TEST_PASSWORD);
            changingPage.locator("button:has-text('Sign in')").click();
            assertBodyContains(changingPage, "Sign-in failed.");

            signIn(changingPage, username, newPassword);
            signIn(otherSessionPage, username, newPassword);
            assertEquals("/app/calendars", URI.create(changingPage.url()).getPath());
            assertEquals("/app/calendars", URI.create(otherSessionPage.url()).getPath());
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void registrationAndInvitationAcceptanceUseUniqueSingleUseRecords() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "invite-owner-" + uniqueSuffix;
        String registrationUsername = "registration-user-" + uniqueSuffix;
        String editorUsername = "invited-editor-" + uniqueSuffix;
        String password = "Registration-password-1-" + uniqueSuffix;
        String ownerCalendarName = "Owner plans " + uniqueSuffix;
        String registrationCalendarName = "Birthday " + uniqueSuffix;
        String editorCalendarName = "Trip " + uniqueSuffix;
        seedUser(ownerUsername);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            signIn(page, ownerUsername, TEST_PASSWORD);
            createCalendar(page, ownerCalendarName);

            String registrationInvitationLink = createRegistrationInvitation(page);
            String editorInvitationLink = createEditorInvitation(page, ownerCalendarName);
            String existingUserInvitationLink = createEditorInvitation(page, ownerCalendarName);
            assertNotEquals(registrationInvitationLink, editorInvitationLink);
            assertNotEquals(editorInvitationLink, existingUserInvitationLink);
            signOut(page);

            page.navigate(route("/register"));
            fillRegistrationForm(
                    page,
                    "blocked-" + uniqueSuffix,
                    "Blocked user " + uniqueSuffix,
                    "Blocked calendar " + uniqueSuffix,
                    password);
            page.locator("button:has-text('Register')").click();
            assertBodyContains(page, "Invitation is invalid or no longer available.");

            String preregistrationSessionIdentifier = requiredSessionCookieValue(browserContext);
            registerNewUser(
                    page,
                    registrationInvitationLink,
                    registrationUsername,
                    "Registration user " + uniqueSuffix,
                    registrationCalendarName,
                    password);
            assertNotEquals(preregistrationSessionIdentifier, requiredSessionCookieValue(browserContext));
            assertBodyContains(page, registrationCalendarName);
            assertFalse(
                    page.locator("body").innerText().contains(ownerCalendarName),
                    "A registration invitation must not grant access to the inviter's calendar.");

            page.navigate(existingUserInvitationLink);
            assertEquals("Accept invitation", page.locator("h1").textContent().trim());
            assertBodyContains(page, "already signed in");
            page.locator("button:has-text('Accept invitation')").click();
            page.waitForURL("**/calendar/*");
            assertBodyContains(page, ownerCalendarName);
            signOut(page);

            registerNewUser(
                    page,
                    editorInvitationLink,
                    editorUsername,
                    "Editor user " + uniqueSuffix,
                    editorCalendarName,
                    password);
            assertBodyContains(page, editorCalendarName);
            assertBodyContains(page, ownerCalendarName);
            assertBodyContains(page, "EDITOR");
            assertBodyContains(page, "ADMIN");

            page.navigate(editorInvitationLink);
            page.locator("button:has-text('Accept invitation')").click();
            assertBodyContains(page, "Invitation is invalid or no longer available.");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void eventCrudAndValidationUseFreshCalendarData() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "event-owner-" + uniqueSuffix;
        String calendarName = "River calendar " + uniqueSuffix;
        String eventTitle = "River launch " + uniqueSuffix;
        String allDayEventTitle = "River weekend " + uniqueSuffix;
        String deletedEventTitle = "Temporary event " + uniqueSuffix;
        seedUser(ownerUsername);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            signIn(page, ownerUsername, TEST_PASSWORD);
            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            String calendarId = Long.toString(findCalendarId(calendarName));

            page.locator("input[id$='eventTitle']").fill("");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, "Event title is required.");
            assertCanonicalCalendarRoute(page, calendarId);

            enterEvent(page, "Invalid event " + uniqueSuffix, null, "2026-07-20 12:00", "2026-07-20 10:00", false);
            assertBodyContains(page, "Event end time must be after the start time.");
            assertEquals(0, page.locator("article", new Page.LocatorOptions().setHasText("Invalid event " + uniqueSuffix)).count());

            enterEvent(page, eventTitle, "North landing", "2026-07-20 10:00", "2026-07-20 12:00", false);
            assertBodyContains(page, eventTitle);
            assertBodyContains(page, "North landing");
            Locator eventRow = page.locator("article", new Page.LocatorOptions().setHasText(eventTitle));
            eventRow.locator("button:has-text('Edit')").click();
            assertThat(page.locator("button:has-text('Save changes')")).isVisible();
            page.locator("input[id$='eventTitle']").fill(eventTitle + " updated");
            assertEquals(eventTitle + " updated", page.locator("input[id$='eventTitle']").inputValue());
            page.locator("button:has-text('Save changes')").click();
            assertBodyContains(page, eventTitle + " updated");

            page.locator("input[id$='eventTitle']").fill(allDayEventTitle);
            page.locator("input[id$='eventStart_input']").fill("2026-07-22 13:30");
            page.locator("input[id$='eventEnd_input']").fill("2026-07-25 00:00");
            Locator allDayCheckbox = page.getByLabel("All-day event");
            page.locator(".checkbox-field .ui-chkbox-box").click();
            assertThat(allDayCheckbox).isChecked();
            Locator firstDayInput = page.locator("input[id$='eventStartDate_input']");
            Locator lastDayInput = page.locator("input[id$='eventEndDate_input']");
            assertThat(firstDayInput).hasValue("2026-07-22");
            assertThat(lastDayInput).hasValue("2026-07-24");
            firstDayInput.fill("2026-07-23");
            lastDayInput.fill("2026-07-25");
            page.locator(".checkbox-field .ui-chkbox-box").click();
            assertThat(allDayCheckbox).not().isChecked();
            assertThat(page.locator("input[id$='eventStart_input']")).hasValue("2026-07-23 00:00");
            assertThat(page.locator("input[id$='eventEnd_input']")).hasValue("2026-07-26 00:00");
            page.locator(".checkbox-field .ui-chkbox-box").click();
            assertThat(allDayCheckbox).isChecked();
            page.locator("button:has-text('Create event')").click();
            assertThat(page.locator("article", new Page.LocatorOptions().setHasText(allDayEventTitle)))
                    .containsText("All day from Thu, Jul 23, 2026 to Sat, Jul 25, 2026");

            enterEvent(page, deletedEventTitle, null, "2026-07-21 14:00", "2026-07-21 15:00", false);
            Locator deletedEventRow = page.locator("article", new Page.LocatorOptions().setHasText(deletedEventTitle));
            deletedEventRow.locator("button:has-text('Delete')").click();
            Locator confirmButton = page.locator("button:has-text('Yes')");
            assertVisibleFocus(confirmButton);
            confirmButton.click();
            assertBodyContains(page, "Event deleted.");
            assertEquals(0, page.locator("article", new Page.LocatorOptions().setHasText(deletedEventTitle)).count());

            page.reload();
            assertCanonicalCalendarRoute(page, calendarId);
            assertBodyContains(page, eventTitle + " updated");
            assertBodyContains(page, allDayEventTitle);

            calendarSettingsLink(page).click();
            page.locator("input[id$='timeZone']").fill("Mars/Olympus");
            page.locator("button:has-text('Save settings')").click();
            assertBodyContains(page, "Time zone must be a valid region such as Europe/Warsaw.");
            String calendarDescription = "Summer river plans " + uniqueSuffix;
            page.locator("input[id$='timeZone']").fill("America/New_York");
            page.locator("textarea[id$='calendarDescription']").fill(calendarDescription);
            page.locator("button:has-text('Save settings')").click();
            assertBodyContains(page, "Calendar settings saved.");
            page.locator("a:has-text('Back to calendar')").click();
            assertThat(page.locator("article", new Page.LocatorOptions().setHasText(allDayEventTitle)))
                    .containsText("All day from Thu, Jul 23, 2026 to Sat, Jul 25, 2026");
            assertThat(page.locator("article", new Page.LocatorOptions().setHasText(eventTitle + " updated")))
                    .containsText("Mon, Jul 20, 2026 at 04:00 to Mon, Jul 20, 2026 at 06:00");
            page.reload();
            assertBodyContains(page, calendarDescription);
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void canonicalCalendarUrlIsSharedReadOnlyAndRegenerationInvalidatesTheOldLink() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "public-owner-" + uniqueSuffix;
        String calendarName = "Public river " + uniqueSuffix;
        String eventTitle = "Public launch " + uniqueSuffix;
        String calendarDescription = "Public plans " + uniqueSuffix;
        seedUser(ownerUsername);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            signIn(page, ownerUsername, TEST_PASSWORD);
            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            String originalCalendarLink = page.url();
            assertCanonicalCalendarRoute(page, Long.toString(findCalendarId(calendarName)));
            enterEvent(page, eventTitle, "River", "2026-08-20 10:00", "2026-08-20 12:00", false);
            calendarSettingsLink(page).click();
            page.locator("textarea[id$='calendarDescription']").fill(calendarDescription);
            page.locator("button:has-text('Save settings')").click();
            assertBodyContains(page, "Calendar settings saved.");
            page.locator("a:has-text('Back to calendar')").click();
            assertEquals(originalCalendarLink, page.url());

            try (BrowserContext publicBrowserContext = browser.newContext()) {
                List<String> publicBrowserMessages = new ArrayList<>();
                Page publicPage = newPage(publicBrowserContext, publicBrowserMessages);
                com.microsoft.playwright.Response publicResponse = publicPage.navigate(originalCalendarLink);
                assertEquals(200, publicResponse.status());
                assertEquals("en", publicPage.locator("html").getAttribute("lang"));
                assertEquals("noindex, nofollow", publicPage.locator("meta[name='robots']").getAttribute("content"));
                assertBodyContains(publicPage, eventTitle);
                assertBodyContains(publicPage, calendarDescription);
                assertBodyContains(publicPage, "Read-only");
                assertEquals(0, publicPage.locator("button:has-text('Create event')").count());
                assertEquals(0, publicPage.locator("button:has-text('Edit')").count());
                assertEquals(0, publicPage.locator("button:has-text('Delete')").count());
                assertEquals(0, calendarSettingsLink(publicPage).count());
                assertEquals(0, publicPage.locator("a:has-text('Members')").count());
                assertNoBrowserMessages(publicBrowserMessages);
            }

            page.locator("button:has-text('Regenerate link')").click();
            page.locator("button:has-text('Yes')").click();
            page.waitForFunction("previousUrl => location.href !== previousUrl", originalCalendarLink);
            String regeneratedCalendarLink = page.url();
            assertNotEquals(originalCalendarLink, regeneratedCalendarLink);
            assertCanonicalCalendarRoute(page, Long.toString(findCalendarId(calendarName)));

            try (BrowserContext regeneratedLinkBrowserContext = browser.newContext()) {
                List<String> oldLinkBrowserMessages = new ArrayList<>();
                Page oldLinkPage = newPage(regeneratedLinkBrowserContext, oldLinkBrowserMessages);
                assertEquals(404, oldLinkPage.navigate(originalCalendarLink).status());
                assertBodyContains(oldLinkPage, "Calendar link unavailable");
                assertBodyContains(oldLinkPage, "This calendar link no longer works.");
                assertBodyContains(oldLinkPage, "Ask a calendar editor for the current link.");
                assertOnlyExpectedNotFoundNavigationMessage(oldLinkBrowserMessages);

                List<String> currentLinkBrowserMessages = new ArrayList<>();
                Page currentLinkPage = newPage(regeneratedLinkBrowserContext, currentLinkBrowserMessages);
                assertEquals(200, currentLinkPage.navigate(regeneratedCalendarLink).status());
                assertBodyContains(currentLinkPage, eventTitle);
                assertBodyContains(currentLinkPage, "Read-only");
                assertNoBrowserMessages(currentLinkBrowserMessages);
            }
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void membershipChangesProtectTheLastAdminAndRemovedEditorsFallBackToTheSharedLink() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "member-owner-" + uniqueSuffix;
        String firstMemberUsername = "member-admin-" + uniqueSuffix;
        String secondMemberUsername = "member-editor-" + uniqueSuffix;
        String password = "Concurrent-password-1-" + uniqueSuffix;
        String calendarName = "Members calendar " + uniqueSuffix;
        String eventTitle = "Members event " + uniqueSuffix;
        seedUser(ownerUsername);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            signIn(page, ownerUsername, TEST_PASSWORD);
            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            String sharedCalendarLink = page.url();
            enterEvent(page, eventTitle, null, "2026-09-20 10:00", "2026-09-20 12:00", false);
            String firstInvitationLink = createEditorInvitation(page, calendarName);
            String secondInvitationLink = createEditorInvitation(page, calendarName);
            signOut(page);

            registerNewUser(
                    page,
                    firstInvitationLink,
                    firstMemberUsername,
                    "First member " + uniqueSuffix,
                    "First member calendar " + uniqueSuffix,
                    password);
            signOut(page);
            registerNewUser(
                    page,
                    secondInvitationLink,
                    secondMemberUsername,
                    "Second member " + uniqueSuffix,
                    "Second member calendar " + uniqueSuffix,
                    password);
            signOut(page);

            signIn(page, ownerUsername, TEST_PASSWORD);
            openCalendar(page, calendarName);
            String calendarId = Long.toString(findCalendarId(calendarName));
            page.locator("a:has-text('Members')").click();
            Locator firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            Locator secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            Locator ownerMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(ownerUsername));

            firstMemberRow.locator("select").selectOption("ADMIN");
            firstMemberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "Member role saved.");
            page.reload();
            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            ownerMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(ownerUsername));
            assertThat(firstMemberRow.locator("select")).hasValue("ADMIN");
            assertFalse(ownerMemberRow.locator("select").isEnabled());
            assertFalse(ownerMemberRow.locator("button:has-text('Save role')").isEnabled());
            assertFalse(ownerMemberRow.locator("button:has-text('Remove access')").isEnabled());
            assertThat(ownerMemberRow).containsText("Your admin role cannot be changed here.");

            firstMemberRow.locator("select").selectOption("EDITOR");
            firstMemberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "Member role saved.");
            page.reload();
            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            assertThat(firstMemberRow.locator("select")).hasValue("EDITOR");

            secondMemberRow.locator("button:has-text('Remove access')").click();
            page.locator("button:has-text('Yes')").click();
            assertBodyContains(page, "Member access removed.");
            page.reload();
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            assertThat(secondMemberRow).containsText("Inactive");
            secondMemberRow.locator("select").selectOption("EDITOR");
            secondMemberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "Member role saved.");
            page.reload();
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            assertThat(secondMemberRow).containsText("Active");

            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            firstMemberRow.locator("button:has-text('Remove access')").click();
            page.locator("button:has-text('Yes')").click();
            assertBodyContains(page, "Member access removed.");
            assertBodyContains(page, "Public-link access is unchanged.");
            page.reload();
            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            assertThat(firstMemberRow).containsText("Inactive");

            List<String> missingIdentifierBrowserMessages = new ArrayList<>();
            Page missingIdentifierPage = newPage(browserContext, missingIdentifierBrowserMessages);
            assertEquals(404, missingIdentifierPage.navigate(route("/app/calendar-members")).status());
            assertBodyContains(missingIdentifierPage, "Calendar not found");
            assertFalse(missingIdentifierPage.locator("body").innerText().contains("Exception thrown"));
            assertOnlyExpectedNotFoundNavigationMessage(missingIdentifierBrowserMessages);
            missingIdentifierPage.close();

            signOut(page);
            signIn(page, firstMemberUsername, password);
            assertFalse(page.locator("body").innerText().contains(calendarName));
            page.navigate(sharedCalendarLink);
            assertCanonicalCalendarRoute(page, calendarId);
            assertBodyContains(page, eventTitle);
            assertBodyContains(page, "Read-only");
            assertEquals(0, page.locator("button:has-text('Create event')").count());
            assertEquals(0, page.locator("button:has-text('Edit')").count());
            assertEquals(0, page.locator("button:has-text('Delete')").count());
            assertEquals(0, calendarSettingsLink(page).count());
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void editorAndAnonymousAuthorizationIsEnforcedAcrossIndependentSessionsAndDirectRoutes() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "role-owner-" + uniqueSuffix;
        String editorUsername = "role-editor-" + uniqueSuffix;
        String unrelatedUsername = "role-unrelated-" + uniqueSuffix;
        String password = "Role-password-1-" + uniqueSuffix;
        String calendarName = "Role matrix " + uniqueSuffix;
        String eventTitle = "Owner event " + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(unrelatedUsername);
        String calendarId;
        String calendarLink;

        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = setupContext.newPage();
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            createCalendar(setupPage, calendarName);
            openCalendar(setupPage, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            calendarLink = setupPage.url();
            enterEvent(setupPage, eventTitle, null, "2026-10-01 10:00", "2026-10-01 11:00", false);
            String editorInvitationLink = createEditorInvitation(setupPage, calendarName);
            signOut(setupPage);

            registerNewUser(
                    setupPage,
                    editorInvitationLink,
                    editorUsername,
                    "Role editor " + uniqueSuffix,
                    "Editor calendar " + uniqueSuffix,
                    password);
            signOut(setupPage);
        }

        try (BrowserContext unauthenticatedContext = browser.newContext()) {
            Page unauthenticatedPage = unauthenticatedContext.newPage();
            for (String protectedRoute : List.of(
                    "/app/calendars",
                    "/app/invitations",
                    "/app/calendar-settings?id=" + calendarId,
                    "/app/calendar-members?id=" + calendarId)) {
                unauthenticatedPage.navigate(route(protectedRoute));
                assertEquals("/login", URI.create(unauthenticatedPage.url()).getPath());
                assertBodyContains(unauthenticatedPage, "Sign in");
            }
            assertEquals(200, unauthenticatedPage.navigate(calendarLink).status());
            assertBodyContains(unauthenticatedPage, eventTitle);
            assertBodyContains(unauthenticatedPage, "Read-only");
            assertEquals(0, unauthenticatedPage.locator("button:has-text('Create event')").count());
        }

        try (BrowserContext editorContext = browser.newContext()) {
            List<String> browserMessages = new ArrayList<>();
            Page editorPage = newPage(editorContext, browserMessages);
            signIn(editorPage, editorUsername, password);
            openCalendar(editorPage, calendarName);
            assertBodyContains(editorPage, eventTitle);
            assertBodyContains(editorPage, "EDITOR");
            assertThat(editorPage.locator("button:has-text('Create event')")).isVisible();
            enterEvent(
                    editorPage,
                    "Editor event " + uniqueSuffix,
                    null,
                    "2026-10-02 10:00",
                    "2026-10-02 11:00",
                    false);
            assertBodyContains(editorPage, "Editor event " + uniqueSuffix);
            assertTrue(createEditorInvitation(editorPage, calendarName).contains("/register?token="));
            openCalendar(editorPage, calendarName);
            String editorOriginalLink = editorPage.url();
            editorPage.locator("button:has-text('Regenerate link')").click();
            editorPage.locator("button:has-text('Yes')").click();
            editorPage.waitForFunction("previousUrl => location.href !== previousUrl", editorOriginalLink);
            calendarLink = editorPage.url();
            editorPage.navigate(route("/app/calendar-settings?id=" + calendarId));
            assertBodyContains(editorPage, "Calendar not found");
            editorPage.navigate(route("/app/calendar-members?id=" + calendarId));
            assertBodyContains(editorPage, "Calendar not found");
            assertOnlyExpectedNotFoundNavigationMessages(browserMessages);
        }

        try (BrowserContext unrelatedContext = browser.newContext()) {
            List<String> browserMessages = new ArrayList<>();
            Page unrelatedPage = newPage(unrelatedContext, browserMessages);
            signIn(unrelatedPage, unrelatedUsername, TEST_PASSWORD);
            assertFalse(unrelatedPage.locator("body").innerText().contains(calendarName));
            unrelatedPage.navigate(calendarLink);
            assertBodyContains(unrelatedPage, eventTitle);
            assertBodyContains(unrelatedPage, "Read-only");
            assertEquals(0, unrelatedPage.locator("button:has-text('Create event')").count());
            assertEquals(0, unrelatedPage.locator("button:has-text('Edit')").count());
            assertEquals(0, unrelatedPage.locator("button:has-text('Delete')").count());
            for (String inaccessibleRoute : List.of(
                    "/app/calendar-settings?id=" + calendarId,
                    "/app/calendar-members?id=" + calendarId)) {
                unrelatedPage.navigate(route(inaccessibleRoute));
                assertBodyContains(unrelatedPage, "Calendar not found");
            }
            unrelatedPage.navigate(route("/app/invitations"));
            assertEquals(
                    0,
                    unrelatedPage.locator(
                            "select[id$='calendar'] option",
                            new Page.LocatorOptions().setHasText(calendarName)).count());
            assertOnlyExpectedNotFoundNavigationMessages(browserMessages);
        }
    }

    @Test
    void invitationStatusesRevocationExpirationInactiveCreatorsAndMalformedLinksAreEnforcedInTheBrowser() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "status-owner-" + uniqueSuffix;
        String password = "Status-password-1-" + uniqueSuffix;
        seedUser(ownerUsername);
        String availableInvitationLink;
        String revokedInvitationLink;
        String expiredInvitationLink;
        String usedInvitationLink;
        String inactiveCreatorInvitationLink;

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            availableInvitationLink = createRegistrationInvitation(ownerPage);
            revokedInvitationLink = createRegistrationInvitation(ownerPage);
            invitationRow(ownerPage, revokedInvitationLink).locator("button:has-text('Revoke')").click();
            assertBodyContains(ownerPage, "Invitation revoked.");
            assertThat(invitationRow(ownerPage, revokedInvitationLink)).containsText("Revoked");
            assertEquals(0, invitationRow(ownerPage, revokedInvitationLink).locator("button:has-text('Revoke')").count());

            expiredInvitationLink = insertExpiredInvitation(ownerUsername);
            ownerPage.navigate(route("/app/invitations"));
            assertThat(invitationRow(ownerPage, expiredInvitationLink)).containsText("Expired");
            assertEquals(0, invitationRow(ownerPage, expiredInvitationLink).locator("button:has-text('Revoke')").count());
            assertThat(invitationRow(ownerPage, availableInvitationLink)).containsText("Available");
            assertThat(invitationRow(ownerPage, availableInvitationLink).locator("button:has-text('Revoke')")).isVisible();

            usedInvitationLink = createRegistrationInvitation(ownerPage);
            try (BrowserContext registrationContext = browser.newContext()) {
                Page registrationPage = registrationContext.newPage();
                registerNewUser(
                        registrationPage,
                        usedInvitationLink,
                        "status-used-" + uniqueSuffix,
                        "Used invitation " + uniqueSuffix,
                        "Used calendar " + uniqueSuffix,
                        password);
            }
            ownerPage.reload();
            assertThat(invitationRow(ownerPage, usedInvitationLink)).containsText("Used");
            assertEquals(0, invitationRow(ownerPage, usedInvitationLink).locator("button:has-text('Revoke')").count());
            inactiveCreatorInvitationLink = createRegistrationInvitation(ownerPage);
        }

        setUserActive(ownerUsername, false);

        try (BrowserContext rejectedContext = browser.newContext()) {
            Page rejectedPage = rejectedContext.newPage();
            assertRegistrationRejected(
                    rejectedPage,
                    revokedInvitationLink,
                    "revoked-" + uniqueSuffix,
                    password,
                    "Invitation is invalid or no longer available.");
            assertRegistrationRejected(
                    rejectedPage,
                    expiredInvitationLink,
                    "expired-" + uniqueSuffix,
                    password,
                    "Invitation is invalid or no longer available.");
            assertRegistrationRejected(
                    rejectedPage,
                    inactiveCreatorInvitationLink,
                    "inactive-creator-" + uniqueSuffix,
                    password,
                    "Invitation is invalid or no longer available.");
            assertRegistrationRejected(
                    rejectedPage,
                    route("/register?token=malformed-" + uniqueSuffix),
                    "malformed-" + uniqueSuffix,
                    password,
                    "Invitation is invalid or no longer available.");
        }
    }

    @Test
    void acceptedInvitationsPreserveStrongerRolesAndReactivateRemovedMembers() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "membership-owner-" + uniqueSuffix;
        String memberUsername = "membership-invitee-" + uniqueSuffix;
        String password = "Membership-password-1-" + uniqueSuffix;
        String calendarName = "Invitation membership " + uniqueSuffix;
        seedUser(ownerUsername);
        String calendarId;

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = browserContext.newPage();
            signIn(page, ownerUsername, TEST_PASSWORD);
            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            String initialInvitationLink = createEditorInvitation(page, calendarName);
            signOut(page);
            registerNewUser(
                    page,
                    initialInvitationLink,
                    memberUsername,
                    "Membership invitee " + uniqueSuffix,
                    "Invitee calendar " + uniqueSuffix,
                    password);
            signOut(page);

            signIn(page, ownerUsername, TEST_PASSWORD);
            page.navigate(route("/app/calendar-members?id=" + calendarId));
            Locator memberRow = page.locator("tr", new Page.LocatorOptions().setHasText(memberUsername));
            memberRow.locator("select").selectOption("ADMIN");
            memberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "Member role saved.");
            String strongerRoleInvitationLink = createEditorInvitation(page, calendarName);
            signOut(page);

            signIn(page, memberUsername, password);
            page.navigate(strongerRoleInvitationLink);
            page.locator("button:has-text('Accept invitation')").click();
            page.waitForURL("**/calendar/*");
            assertBodyContains(page, "ADMIN");
            signOut(page);

            signIn(page, ownerUsername, TEST_PASSWORD);
            page.navigate(route("/app/calendar-members?id=" + calendarId));
            memberRow = page.locator("tr", new Page.LocatorOptions().setHasText(memberUsername));
            memberRow.locator("button:has-text('Remove access')").click();
            page.locator("button:has-text('Yes')").click();
            assertBodyContains(page, "Member access removed.");
            String reactivationInvitationLink = createEditorInvitation(page, calendarName);
            signOut(page);

            signIn(page, memberUsername, password);
            page.navigate(reactivationInvitationLink);
            page.locator("button:has-text('Accept invitation')").click();
            page.waitForURL("**/calendar/*");
            assertBodyContains(page, calendarName);
            assertBodyContains(page, "EDITOR");
        }
    }

    @Test
    void removedEditorsCannotUseSavedSelfInvitationsAndAdminsCanRevokeEditorInvitations() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "saved-invite-owner-" + uniqueSuffix;
        String editorUsername = "saved-invite-editor-" + uniqueSuffix;
        String calendarName = "Saved invitation " + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(editorUsername);
        String calendarId;
        String membershipInvitationLink;

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            createCalendar(ownerPage, calendarName);
            openCalendar(ownerPage, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            membershipInvitationLink = createEditorInvitation(ownerPage, calendarName);
        }

        try (BrowserContext editorContext = browser.newContext(); BrowserContext ownerContext = browser.newContext()) {
            Page editorPage = editorContext.newPage();
            Page ownerPage = ownerContext.newPage();
            signIn(editorPage, editorUsername, TEST_PASSWORD);
            editorPage.navigate(membershipInvitationLink);
            editorPage.locator("button:has-text('Accept invitation')").click();
            editorPage.waitForURL("**/calendar/*");
            String savedSelfInvitationLink = createEditorInvitation(editorPage, calendarName);
            String administratorRevocationLink = createEditorInvitation(editorPage, calendarName);

            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            ownerPage.navigate(route("/app/calendar-members?id=" + calendarId));
            Locator editorRow = ownerPage.locator("tr", new Page.LocatorOptions().setHasText(editorUsername));
            editorRow.locator("button:has-text('Remove access')").click();
            ownerPage.locator("button:has-text('Yes')").click();
            assertBodyContains(ownerPage, "Member access removed.");

            ownerPage.navigate(route("/app/invitations"));
            assertThat(invitationRow(ownerPage, savedSelfInvitationLink)).isVisible();
            Locator administratorRevocationRow = invitationRow(ownerPage, administratorRevocationLink);
            assertThat(administratorRevocationRow).isVisible();
            administratorRevocationRow.locator("button:has-text('Revoke')").click();
            assertThat(invitationRow(ownerPage, administratorRevocationLink)).containsText("Revoked");

            editorPage.navigate(savedSelfInvitationLink);
            editorPage.locator("button:has-text('Accept invitation')").click();
            assertBodyContains(editorPage, "Invitation is invalid or no longer available.");
            editorPage.navigate(calendarLink(calendarId));
            assertBodyContains(editorPage, calendarName);
            assertBodyContains(editorPage, "Read-only");
            assertEquals(0, editorPage.locator("button:has-text('Create event')").count());
        }
    }

    @Test
    void concurrentDistinctInvitationsForOneUserCreateOnlyOneMembershipWithoutFailure() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "distinct-invite-owner-" + uniqueSuffix;
        String candidateUsername = "distinct-invite-candidate-" + uniqueSuffix;
        String calendarName = "Distinct invitations " + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(candidateUsername);
        String firstInvitationLink;
        String secondInvitationLink;

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            createCalendar(ownerPage, calendarName);
            firstInvitationLink = createEditorInvitation(ownerPage, calendarName);
            secondInvitationLink = createEditorInvitation(ownerPage, calendarName);
        }

        try (BrowserContext firstContext = browser.newContext(); BrowserContext secondContext = browser.newContext()) {
            Page firstPage = firstContext.newPage();
            Page secondPage = secondContext.newPage();
            signIn(firstPage, candidateUsername, TEST_PASSWORD);
            signIn(secondPage, candidateUsername, TEST_PASSWORD);
            firstPage.navigate(firstInvitationLink);
            secondPage.navigate(secondInvitationLink);
            Locator firstAcceptButton = firstPage.locator("button:has-text('Accept invitation')");
            Locator secondAcceptButton = secondPage.locator("button:has-text('Accept invitation')");
            assertThat(firstAcceptButton).isVisible();
            assertThat(secondAcceptButton).isVisible();

            long scheduledAcceptanceTime = System.currentTimeMillis() + 750;
            scheduleClickAt(firstAcceptButton, scheduledAcceptanceTime);
            scheduleClickAt(secondAcceptButton, scheduledAcceptanceTime);
            waitForInvitationAcceptanceResult(firstPage);
            waitForInvitationAcceptanceResult(secondPage);

            assertTrue(isCanonicalCalendarPath(firstPage.url()));
            assertTrue(isCanonicalCalendarPath(secondPage.url()));
            assertBodyContains(firstPage, calendarName);
            assertBodyContains(secondPage, calendarName);
            assertBodyContains(firstPage, "EDITOR");
            assertBodyContains(secondPage, "EDITOR");
        }
    }

    @Test
    void concurrentInvitationAcceptanceAllowsExactlyOneUserAndRemovedEditorsLoseMutationAccess()
            throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "acceptance-owner-" + uniqueSuffix;
        String firstCandidateUsername = "acceptance-first-" + uniqueSuffix;
        String secondCandidateUsername = "acceptance-second-" + uniqueSuffix;
        String calendarName = "Contested invitation " + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(firstCandidateUsername);
        seedUser(secondCandidateUsername);
        String calendarId;
        String invitationLink;

        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = setupContext.newPage();
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            createCalendar(setupPage, calendarName);
            openCalendar(setupPage, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            invitationLink = createEditorInvitation(setupPage, calendarName);
        }

        try (BrowserContext firstContext = browser.newContext();
                BrowserContext secondContext = browser.newContext();
                BrowserContext ownerContext = browser.newContext()) {
            Page firstPage = firstContext.newPage();
            Page secondPage = secondContext.newPage();
            Page ownerPage = ownerContext.newPage();
            signIn(firstPage, firstCandidateUsername, TEST_PASSWORD);
            signIn(secondPage, secondCandidateUsername, TEST_PASSWORD);
            firstPage.navigate(invitationLink);
            secondPage.navigate(invitationLink);
            Locator firstAcceptButton = firstPage.locator("button:has-text('Accept invitation')");
            Locator secondAcceptButton = secondPage.locator("button:has-text('Accept invitation')");
            assertThat(firstAcceptButton).isVisible();
            assertThat(secondAcceptButton).isVisible();

            long scheduledAcceptanceTime = System.currentTimeMillis() + 750;
            scheduleClickAt(firstAcceptButton, scheduledAcceptanceTime);
            scheduleClickAt(secondAcceptButton, scheduledAcceptanceTime);
            waitForInvitationAcceptanceResult(firstPage);
            waitForInvitationAcceptanceResult(secondPage);

            boolean firstCandidateAccepted = isCanonicalCalendarPath(firstPage.url());
            boolean secondCandidateAccepted = isCanonicalCalendarPath(secondPage.url());
            assertNotEquals(
                    firstCandidateAccepted,
                    secondCandidateAccepted,
                    "Exactly one contender should consume a single-use invitation.");

            Page acceptedPage = firstCandidateAccepted ? firstPage : secondPage;
            Page rejectedPage = firstCandidateAccepted ? secondPage : firstPage;
            String acceptedUsername = firstCandidateAccepted ? firstCandidateUsername : secondCandidateUsername;
            String rejectedUsername = firstCandidateAccepted ? secondCandidateUsername : firstCandidateUsername;
            assertBodyContains(acceptedPage, calendarName);
            assertBodyContains(acceptedPage, "EDITOR");
            assertBodyContains(rejectedPage, "Invitation is invalid or no longer available.");

            rejectedPage.navigate(route("/app/invitations"));
            assertEquals(
                    0,
                    rejectedPage.locator("input[value=\"" + invitationLink + "\"]").count(),
                    "Another user's invitation must not be exposed for revocation.");

            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            ownerPage.navigate(route("/app/calendar-members?id=" + calendarId));
            Locator acceptedMemberRow = ownerPage.locator("tr", new Page.LocatorOptions().setHasText(acceptedUsername));
            assertThat(acceptedMemberRow).containsText("Active");
            assertEquals(
                    0,
                    ownerPage.locator("tr", new Page.LocatorOptions().setHasText(rejectedUsername)).count(),
                    "The losing contender must not receive membership.");
            acceptedMemberRow.locator("button:has-text('Remove access')").click();
            ownerPage.locator("button:has-text('Yes')").click();
            assertBodyContains(ownerPage, "Member access removed.");

            acceptedPage.reload();
            assertBodyContains(acceptedPage, calendarName);
            assertBodyContains(acceptedPage, "Read-only");
            assertEquals(0, acceptedPage.locator("button:has-text('Create event')").count());
        }
    }

    @Test
    void concurrentAdministratorsCannotDemoteEachOtherAndLeaveTheCalendarWithoutAnAdmin() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "admin-race-owner-" + uniqueSuffix;
        String secondAdminUsername = "admin-race-second-" + uniqueSuffix;
        String password = "Admin-race-password-1-" + uniqueSuffix;
        String calendarName = "Admin race " + uniqueSuffix;
        seedUser(ownerUsername);
        String calendarId;

        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = setupContext.newPage();
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            createCalendar(setupPage, calendarName);
            openCalendar(setupPage, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            String invitationLink = createEditorInvitation(setupPage, calendarName);
            signOut(setupPage);
            registerNewUser(
                    setupPage,
                    invitationLink,
                    secondAdminUsername,
                    "Second administrator " + uniqueSuffix,
                    "Second administrator calendar " + uniqueSuffix,
                    password);
            signOut(setupPage);
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            setupPage.navigate(route("/app/calendar-members?id=" + calendarId));
            Locator secondAdminRow = setupPage.locator("tr", new Page.LocatorOptions().setHasText(secondAdminUsername));
            secondAdminRow.locator("select").selectOption("ADMIN");
            secondAdminRow.locator("button:has-text('Save role')").click();
            assertBodyContains(setupPage, "Member role saved.");
        }

        try (BrowserContext ownerContext = browser.newContext(); BrowserContext secondAdminContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            Page secondAdminPage = secondAdminContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            signIn(secondAdminPage, secondAdminUsername, password);
            ownerPage.navigate(route("/app/calendar-members?id=" + calendarId));
            secondAdminPage.navigate(route("/app/calendar-members?id=" + calendarId));

            Locator ownerTargetingSecondAdmin = ownerPage.locator(
                    "tr", new Page.LocatorOptions().setHasText(secondAdminUsername));
            Locator secondAdminTargetingOwner = secondAdminPage.locator(
                    "tr", new Page.LocatorOptions().setHasText(ownerUsername));
            ownerTargetingSecondAdmin.locator("select").selectOption("EDITOR");
            secondAdminTargetingOwner.locator("select").selectOption("EDITOR");

            long scheduledRoleChangeTime = System.currentTimeMillis() + 750;
            scheduleClickAt(
                    ownerTargetingSecondAdmin.locator("button:has-text('Save role')"), scheduledRoleChangeTime);
            scheduleClickAt(
                    secondAdminTargetingOwner.locator("button:has-text('Save role')"), scheduledRoleChangeTime);
            waitForMembershipChangeResult(ownerPage);
            waitForMembershipChangeResult(secondAdminPage);

            boolean ownerSucceeded = ownerPage.locator("body").innerText().contains("Member role saved.");
            boolean secondAdminSucceeded = secondAdminPage.locator("body").innerText().contains("Member role saved.");
            assertNotEquals(
                    ownerSucceeded,
                    secondAdminSucceeded,
                    "Only one concurrent admin demotion may succeed.");
            Page survivingAdminPage = ownerSucceeded ? ownerPage : secondAdminPage;
            Page demotedAdminPage = ownerSucceeded ? secondAdminPage : ownerPage;
            assertBodyContains(demotedAdminPage, "Admin access is required.");
            assertEquals(1L, countActiveAdmins(Long.parseLong(calendarId)));

            survivingAdminPage.navigate(route("/app/calendar-members?id=" + calendarId));
            assertBodyContains(survivingAdminPage, calendarName);
            demotedAdminPage.navigate(route("/app/calendar-members?id=" + calendarId));
            assertBodyContains(demotedAdminPage, "Calendar not found");
            demotedAdminPage.navigate(calendarLink(calendarId));
            assertBodyContains(demotedAdminPage, calendarName);
            assertBodyContains(demotedAdminPage, "EDITOR");
            assertThat(demotedAdminPage.locator("button:has-text('Create event')")).isVisible();
        }
    }

    @Test
    void publicAccessCanBeDisabledReenabledAndIsBlockedForInactiveCalendars() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "public-toggle-owner-" + uniqueSuffix;
        String calendarName = "Public toggle " + uniqueSuffix;
        String eventTitle = "Toggle event " + uniqueSuffix;
        seedUser(ownerUsername);

        try (BrowserContext ownerContext = browser.newContext(); BrowserContext publicContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            Page publicPage = publicContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            createCalendar(ownerPage, calendarName);
            openCalendar(ownerPage, calendarName);
            String sharedCalendarLink = ownerPage.url();
            String calendarId = Long.toString(findCalendarId(calendarName));
            enterEvent(ownerPage, eventTitle, null, "2026-11-01 10:00", "2026-11-01 11:00", false);
            calendarSettingsLink(ownerPage).click();

            assertEquals(200, publicPage.navigate(sharedCalendarLink).status());
            assertBodyContains(publicPage, eventTitle);

            setPublicAccess(ownerPage, false);
            assertBodyContains(ownerPage, "Public access disabled");
            ownerPage.reload();
            assertFalse(ownerPage.getByLabel("Enable public read-only access").isChecked());
            assertEquals(404, publicPage.navigate(sharedCalendarLink).status());
            assertBodyContains(publicPage, "Calendar link unavailable");
            assertBodyContains(publicPage, "public access may be disabled");

            assertEquals(200, ownerPage.navigate(sharedCalendarLink).status());
            assertBodyContains(ownerPage, eventTitle);
            assertBodyContains(ownerPage, "Public access disabled");
            assertThat(ownerPage.locator("button:has-text('Create event')")).isVisible();
            calendarSettingsLink(ownerPage).click();

            setPublicAccess(ownerPage, true);
            assertBodyContains(ownerPage, "Public access enabled");
            ownerPage.reload();
            assertTrue(ownerPage.getByLabel("Enable public read-only access").isChecked());
            assertEquals(200, publicPage.navigate(sharedCalendarLink).status());
            assertBodyContains(publicPage, eventTitle);

            setCalendarActive(Long.parseLong(calendarId), false);
            assertEquals(404, publicPage.navigate(sharedCalendarLink).status());
            assertEquals(404, ownerPage.navigate(sharedCalendarLink).status());
            assertBodyContains(ownerPage, "Calendar link unavailable");
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
            registrationPage.navigate(invitationLinks.get(0));
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
            registrationPage.navigate(invitationLinks.get(1));
            fillRegistrationForm(
                    registrationPage,
                    matchingUsername,
                    "Matching password user",
                    "Matching password calendar",
                    matchingUsername);
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Password must not match the username.");

            registrationPage.navigate(invitationLinks.get(2));
            fillRegistrationForm(
                    registrationPage,
                    duplicateUsername.toUpperCase(Locale.ROOT),
                    "Duplicate user",
                    "Duplicate calendar",
                    "Valid-duplicate-password-1-" + uniqueSuffix);
            registrationPage.locator("button:has-text('Register')").click();
            assertBodyContains(registrationPage, "Username is already registered.");

            registrationPage.navigate(invitationLinks.get(3));
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
            inactivePage.navigate(route("/login"));
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
            sessionPage.navigate(route("/app/calendars"));
            assertEquals("/login", URI.create(sessionPage.url()).getPath());
            assertBodyContains(sessionPage, "Sign in");
        }
    }

    @Test
    void calendarAndEventBoundaryValidationResetAndNotFoundRoutesAreCovered() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "boundary-owner-" + uniqueSuffix;
        String calendarName = "Boundary calendar " + uniqueSuffix;
        String eventTitle = "Boundary event " + uniqueSuffix;
        seedUser(ownerUsername);

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = browserContext.newPage();
            signIn(page, ownerUsername, TEST_PASSWORD);
            Locator calendarNameInput = page.locator("input[id$='calendarName']");
            assertEquals("160", calendarNameInput.getAttribute("maxlength"));
            calendarNameInput.fill("   ");
            page.locator("button:has-text('Create calendar')").click();
            assertBodyContains(page, "Calendar name is required.");
            assertEquals("polite", page.locator(".ui-messages").getAttribute("aria-live"));

            setRawInputValue(calendarNameInput, "c".repeat(161));
            assertEquals(161, calendarNameInput.inputValue().length());
            clickWithoutChangingFocus(page.locator("button:has-text('Create calendar')"));
            assertBodyContains(page, "Calendar name must be 160 characters or fewer.");

            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            String calendarId = Long.toString(findCalendarId(calendarName));
            assertEquals("200", page.locator("input[id$='eventTitle']").getAttribute("maxlength"));
            assertEquals("200", page.locator("input[id$='eventLocation']").getAttribute("maxlength"));

            Locator eventTitleInput = page.locator("input[id$='eventTitle']");
            Locator eventLocationInput = page.locator("input[id$='eventLocation']");
            Locator eventStartInput = page.locator("input[id$='eventStart_input']");
            Locator eventEndInput = page.locator("input[id$='eventEnd_input']");

            eventStartInput.fill("2026-11-30 10:00");
            eventEndInput.fill("2026-11-30 11:00");
            setRawInputValue(eventTitleInput, "t".repeat(201));
            assertEquals(201, eventTitleInput.inputValue().length());
            clickWithoutChangingFocus(page.locator("button:has-text('Create event')"));
            assertBodyContains(page, "Event title must be 200 characters or fewer.");

            eventTitleInput.fill("Oversized location " + uniqueSuffix);
            eventStartInput.fill("2026-11-30 10:00");
            eventEndInput.fill("2026-11-30 11:00");
            setRawInputValue(eventLocationInput, "l".repeat(201));
            assertEquals(201, eventLocationInput.inputValue().length());
            clickWithoutChangingFocus(page.locator("button:has-text('Create event')"));
            assertBodyContains(page, "Event location must be 200 characters or fewer.");

            eventTitleInput.fill("Missing start " + uniqueSuffix);
            eventLocationInput.fill("");
            eventStartInput.fill("");
            eventEndInput.fill("2026-11-30 11:00");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, "Event start time is required.");

            eventTitleInput.fill("Missing end " + uniqueSuffix);
            eventStartInput.fill("2026-11-30 10:00");
            eventEndInput.fill("");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, "Event end time is required.");

            enterEvent(page, "Equal times " + uniqueSuffix, null, "2026-12-01 10:00", "2026-12-01 10:00", false);
            assertBodyContains(page, "Event end time must be after the start time.");

            page.locator("input[id$='eventTitle']").fill("Clock gap " + uniqueSuffix);
            setRawInputValue(page.locator("input[id$='eventStart_input']"), "2026-03-29 02:30");
            setRawInputValue(page.locator("input[id$='eventEnd_input']"), "2026-03-29 04:00");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, "The selected time does not exist in the calendar time zone because of a clock change.");
            page.locator("input[id$='eventTitle']").fill("Clock overlap " + uniqueSuffix);
            setRawInputValue(page.locator("input[id$='eventStart_input']"), "2026-10-25 02:30");
            setRawInputValue(page.locator("input[id$='eventEnd_input']"), "2026-10-25 04:00");
            page.locator("button:has-text('Create event')").click();
            assertBodyContains(page, "The selected time occurs twice in the calendar time zone because of a clock change.");

            enterEvent(page, eventTitle, "Boundary location", "2026-12-02 10:00", "2026-12-02 11:00", false);
            Locator eventRow = page.locator("article", new Page.LocatorOptions().setHasText(eventTitle));
            eventRow.locator("button:has-text('Edit')").click();
            assertThat(page.locator("button:has-text('Save changes')")).isVisible();
            setRawInputValue(eventStartInput, "2026-12-02 12:00");
            setRawInputValue(eventEndInput, "2026-12-02 12:00");
            assertEquals("2026-12-02 12:00", eventStartInput.inputValue());
            assertEquals("2026-12-02 12:00", eventEndInput.inputValue());
            page.locator("button:has-text('Save changes')").click();
            assertBodyContains(page, "Event end time must be after the start time.");
            page.locator("button:has-text('Cancel edit')").click();
            assertThat(page.locator("button:has-text('Create event')")).isVisible();
            assertEquals("", page.locator("input[id$='eventTitle']").inputValue());
            page.reload();
            assertBodyContains(page, eventTitle);
            assertThat(page.locator("article", new Page.LocatorOptions().setHasText(eventTitle)))
                    .containsText("Boundary location");

            for (String routePath : List.of("/app/calendar-settings", "/app/calendar-members")) {
                page.navigate(route(routePath));
                assertBodyContains(page, "Calendar not found");
                page.navigate(route(routePath + "?id=999999999"));
                assertBodyContains(page, "Calendar not found");
                page.navigate(route(routePath + "?id=not-a-number"));
                assertFalse(page.locator("body").innerText().contains("Exception thrown"));
            }
            page.navigate(calendarLink(calendarId));
            assertBodyContains(page, eventTitle);
        }
    }

    @Test
    void concurrentSessionsRejectStaleEventAndCalendarUpdatesWithoutLostChanges() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "concurrency-owner-" + uniqueSuffix;
        String calendarName = "Concurrent calendar " + uniqueSuffix;
        String eventTitle = "Concurrent event " + uniqueSuffix;
        String deletionEventTitle = "Concurrent deletion " + uniqueSuffix;
        seedUser(ownerUsername);
        String calendarId;

        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = setupContext.newPage();
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            createCalendar(setupPage, calendarName);
            openCalendar(setupPage, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            enterEvent(setupPage, eventTitle, null, "2026-12-10 10:00", "2026-12-10 11:00", false);
            assertBodyContains(setupPage, eventTitle);
            enterEvent(setupPage, deletionEventTitle, null, "2026-12-11 10:00", "2026-12-11 11:00", false);
            assertBodyContains(setupPage, deletionEventTitle);
        }

        try (BrowserContext firstContext = browser.newContext();
                BrowserContext secondContext = browser.newContext();
                BrowserContext staleDeleteContext = browser.newContext()) {
            Page firstPage = firstContext.newPage();
            Page secondPage = secondContext.newPage();
            Page staleDeletePage = staleDeleteContext.newPage();
            signIn(firstPage, ownerUsername, TEST_PASSWORD);
            signIn(secondPage, ownerUsername, TEST_PASSWORD);
            signIn(staleDeletePage, ownerUsername, TEST_PASSWORD);
            firstPage.navigate(calendarLink(calendarId));
            secondPage.navigate(calendarLink(calendarId));
            staleDeletePage.navigate(calendarLink(calendarId));
            firstPage.locator("article", new Page.LocatorOptions().setHasText(eventTitle))
                    .locator("button:has-text('Edit')")
                    .click();
            assertThat(firstPage.locator("button:has-text('Save changes')")).isVisible();
            secondPage.locator("article", new Page.LocatorOptions().setHasText(eventTitle))
                    .locator("button:has-text('Edit')")
                    .click();
            assertThat(secondPage.locator("button:has-text('Save changes')")).isVisible();

            firstPage.locator("input[id$='eventTitle']").fill(eventTitle + " first");
            assertEquals(eventTitle + " first", firstPage.locator("input[id$='eventTitle']").inputValue());
            firstPage.locator("button:has-text('Save changes')").click();
            assertBodyContains(firstPage, eventTitle + " first");
            secondPage.locator("input[id$='eventTitle']").fill(eventTitle + " second");
            assertEquals(eventTitle + " second", secondPage.locator("input[id$='eventTitle']").inputValue());
            secondPage.locator("button:has-text('Save changes')").click();
            assertBodyContains(secondPage, "This event changed after you opened it. Reload the page and try again.");
            secondPage.reload();
            assertBodyContains(secondPage, eventTitle + " first");
            assertFalse(secondPage.locator("body").innerText().contains(eventTitle + " second"));

            Locator deletionEventRow = firstPage.locator(
                    "article", new Page.LocatorOptions().setHasText(deletionEventTitle));
            deletionEventRow.locator("button:has-text('Edit')").click();
            assertThat(firstPage.locator("button:has-text('Save changes')")).isVisible();
            firstPage.locator("input[id$='eventTitle']").fill(deletionEventTitle + " updated");
            firstPage.locator("button:has-text('Save changes')").click();
            assertBodyContains(firstPage, deletionEventTitle + " updated");

            Locator staleDeletionEventRow = staleDeletePage.locator(
                    "article", new Page.LocatorOptions().setHasText(deletionEventTitle));
            staleDeletionEventRow.locator("button:has-text('Delete')").click();
            staleDeletePage.locator("button:has-text('Yes')").click();
            assertBodyContains(
                    staleDeletePage,
                    "This event changed after you opened it. Reload the page and try again.");
            staleDeletePage.reload();
            assertBodyContains(staleDeletePage, deletionEventTitle + " updated");

            firstPage.navigate(route("/app/calendar-settings?id=" + calendarId));
            secondPage.navigate(route("/app/calendar-settings?id=" + calendarId));
            firstPage.locator("textarea[id$='calendarDescription']").fill("First settings update " + uniqueSuffix);
            firstPage.locator("button:has-text('Save settings')").click();
            assertBodyContains(firstPage, "Calendar settings saved.");
            secondPage.locator("textarea[id$='calendarDescription']").fill("Second settings update " + uniqueSuffix);
            secondPage.locator("button:has-text('Save settings')").click();
            assertBodyContains(secondPage, "This calendar changed after you opened it. Reload the page and try again.");
            secondPage.reload();
            assertBodyContains(secondPage, "First settings update " + uniqueSuffix);
            assertFalse(secondPage.locator("body").innerText().contains("Second settings update " + uniqueSuffix));
        }
    }

    @Test
    void semanticFocusReducedMotionAndResponsiveLayoutsRemainAccessible() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "layout-owner-" + uniqueSuffix;
        String longCalendarName = "Calendar-" + "x".repeat(140) + uniqueSuffix;
        String longDisplayName = "Member-" + "m".repeat(145) + uniqueSuffix;
        String longEventTitle = "Event-" + "t".repeat(180) + uniqueSuffix;
        String longEventLocation = "Location-" + "l".repeat(180) + uniqueSuffix;
        String longEventDescription = "Description-" + "d".repeat(320) + uniqueSuffix;
        seedUser(ownerUsername, longDisplayName, true);
        String publicCalendarLink;
        String calendarId;

        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = setupContext.newPage();
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            createCalendar(setupPage, longCalendarName);
            openCalendar(setupPage, longCalendarName);
            publicCalendarLink = setupPage.url();
            calendarId = Long.toString(findCalendarId(longCalendarName));
            enterEventWithDescription(
                    setupPage,
                    longEventTitle,
                    longEventDescription,
                    longEventLocation,
                    "2026-12-20 10:00",
                    "2026-12-20 11:00");
            createRegistrationInvitation(setupPage);
            createEditorInvitation(setupPage, longCalendarName);
            setupPage.navigate(calendarLink(calendarId));
            Locator longEventRow = setupPage.locator("article", new Page.LocatorOptions().setHasText(longEventTitle));
            longEventRow.locator("button:has-text('Delete')").click();
            assertVisibleFocus(setupPage.locator("button:has-text('No')"));
            setupPage.keyboard().press("Escape");
            assertThat(setupPage.locator("button:has-text('Yes')")).isHidden();
            assertBodyContains(setupPage, longEventTitle);
            calendarSettingsLink(setupPage).click();
            Locator publicAccessCheckbox = setupPage.getByLabel("Enable public read-only access");
            publicAccessCheckbox.focus();
            assertVisibleOutline(setupPage.locator(".ui-chkbox-box"));
        }

        try (BrowserContext semanticContext = browser.newContext()) {
            List<String> browserMessages = new ArrayList<>();
            Page page = newPage(semanticContext, browserMessages);
            page.navigate(route("/"));
            assertEquals("en", page.locator("html").getAttribute("lang"));
            assertEquals(1, page.locator("h1").count());
            assertEquals("H1", page.locator("h1, h2, h3").first().evaluate("element => element.tagName"));
            assertEquals(0, page.locator(".calendar-preview table").count());
            assertBodyContains(page, "Create an account to start planning.");
            Locator skipLink = page.locator(".skip-link");
            page.keyboard().press("Tab");
            assertEquals(true, skipLink.evaluate("element => document.activeElement === element"));
            assertThat(skipLink).isVisible();
            page.keyboard().press("Enter");
            assertEquals("main-content", page.evaluate("document.activeElement.id"));

            page.navigate(route("/login"));
            Locator usernameInput = page.locator("input[id$='username']");
            Locator passwordInput = page.locator("input[id$='password']");
            Locator signInButton = page.locator("button:has-text('Sign in')");
            pressTabUntilFocused(page, usernameInput, 10);
            page.keyboard().press("Tab");
            assertEquals(true, passwordInput.evaluate("element => document.activeElement === element"));
            page.keyboard().press("Tab");
            assertEquals(true, signInButton.evaluate("element => document.activeElement === element"));
            assertVisibleOutline(signInButton);
            assertEquals(1, page.locator("h1").count());
            assertEquals("Sign in", page.locator("h1").textContent().trim());
            page.navigate(route("/register"));
            assertEquals(1, page.locator("h1").count());
            assertEquals("Create your calendar", page.locator("h1").textContent().trim());
            page.navigate(route("/login-error"));
            assertEquals(1, page.locator("h1").count());
            assertEquals("Sign-in error", page.locator("h1").textContent().trim());
            assertNoBrowserMessages(browserMessages);
        }

        for (int viewportWidth : RESPONSIVE_WIDTHS) {
            List<String> browserMessages = new ArrayList<>();
            Browser.NewContextOptions options = new Browser.NewContextOptions().setViewportSize(viewportWidth, 900);
            try (BrowserContext browserContext = browser.newContext(options)) {
                Page page = newPage(browserContext, browserMessages);
                page.navigate(publicCalendarLink);
                assertEquals("en", page.locator("html").getAttribute("lang"));
                assertBodyContains(page, longCalendarName);
                assertBodyContains(page, longEventTitle);
                assertBodyContains(page, longEventLocation);
                assertBodyContains(page, longEventDescription);
                assertFalse(hasHorizontalOverflow(page), "Public page overflowed at " + viewportWidth + " pixels.");
                assertHeaderPosition(page, viewportWidth);

                signIn(page, ownerUsername, TEST_PASSWORD);
                assertBodyContains(page, longCalendarName);
                assertFalse(hasHorizontalOverflow(page), "Authenticated page overflowed at " + viewportWidth + " pixels.");
                assertHeaderPosition(page, viewportWidth);
                assertResponsiveTableRegion(page, "Calendars table", viewportWidth);

                page.navigate(route("/app/account-settings"));
                assertEquals("Account settings", page.locator("h1").textContent().trim());
                assertFalse(hasHorizontalOverflow(page), "Account settings overflowed at " + viewportWidth + " pixels.");

                page.navigate(calendarLink(calendarId));
                assertBodyContains(page, longEventTitle);
                assertBodyContains(page, longEventLocation);
                assertBodyContains(page, longEventDescription);
                assertFalse(hasHorizontalOverflow(page), "Calendar page overflowed at " + viewportWidth + " pixels.");

                page.navigate(route("/app/invitations"));
                assertResponsiveTableRegion(page, "Invitations table", viewportWidth);
                assertFalse(hasHorizontalOverflow(page), "Invitations page overflowed at " + viewportWidth + " pixels.");

                page.navigate(route("/app/calendar-members?id=" + calendarId));
                assertBodyContains(page, longDisplayName);
                assertResponsiveTableRegion(page, "Calendar members table", viewportWidth);
                assertFalse(hasHorizontalOverflow(page), "Members page overflowed at " + viewportWidth + " pixels.");
                assertNoBrowserMessages(browserMessages);
            }
        }

        try (BrowserContext reducedMotionContext = browser.newContext(
                new Browser.NewContextOptions().setReducedMotion(ReducedMotion.REDUCE))) {
            Page reducedMotionPage = reducedMotionContext.newPage();
            reducedMotionPage.navigate(route("/"));
            assertEquals(true, reducedMotionPage.evaluate("matchMedia('(prefers-reduced-motion: reduce)').matches"));
            assertEquals(
                    true,
                    reducedMotionPage.locator(".skip-link").evaluate(
                            "element => getComputedStyle(element).animationName === 'none' "
                                    + "&& getComputedStyle(element).transitionDuration === '0s'"));
        }
    }

    @Test
    void invalidCalendarLinkReturnsClearNoindexNotFoundPage() {
        List<String> browserMessages = new ArrayList<>();
        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            com.microsoft.playwright.Response response = page.navigate(route("/calendar/not-a-valid-calendar-token"));
            assertEquals(404, response.status());
            assertEquals("Calendar link unavailable - Shared calendar", page.title());
            assertEquals("Calendar link unavailable", page.locator("h1").textContent().trim());
            assertEquals("noindex, nofollow", page.locator("meta[name='robots']").getAttribute("content"));
            assertBodyContains(page, "This calendar link no longer works.");
            assertBodyContains(page, "Ask a calendar editor for the current link.");
            assertFalse(hasHorizontalOverflow(page));
            assertOnlyExpectedNotFoundNavigationMessage(browserMessages);
        }
    }

    private String createRegistrationInvitation(Page page) {
        page.navigate(route("/app/invitations"));
        page.locator("button:has-text('Generate registration link')").click();
        Locator generatedInvitationLink = page.locator("input[id$='generatedInvitationLink']");
        assertThat(generatedInvitationLink).isVisible();
        String invitationLink = generatedInvitationLink.inputValue();
        assertTrue(invitationLink.contains("/register?token="));
        return invitationLink;
    }

    private Locator invitationRow(Page page, String invitationLink) {
        Locator invitationLinkInput = page.locator("input[value=\"" + invitationLink + "\"]");
        return page.locator("tr").filter(new Locator.FilterOptions().setHas(invitationLinkInput));
    }

    private Locator calendarSettingsLink(Page page) {
        return page.locator("a[href*='/app/calendar-settings']");
    }

    private String createEditorInvitation(Page page, String calendarName) {
        page.navigate(route("/app/invitations"));
        Locator calendarSelect = page.locator("select[id$='calendar']");
        assertVisibleFocus(calendarSelect);
        String calendarOptionValue = page.locator(
                        "select[id$='calendar'] option",
                        new Page.LocatorOptions().setHasText(calendarName))
                .getAttribute("value");
        calendarSelect.selectOption(calendarOptionValue);
        page.locator("button:has-text('Generate editor link')").click();
        String invitationLink = page.locator("input[id$='generatedInvitationLink']").inputValue();
        assertTrue(invitationLink.contains("/register?token="));
        return invitationLink;
    }

    private void registerNewUser(
            Page page,
            String invitationLink,
            String username,
            String displayName,
            String calendarName,
            String password) {
        page.navigate(invitationLink);
        fillRegistrationForm(page, username, displayName, calendarName, password);
        page.locator("button:has-text('Register')").click();
        page.waitForURL("**/app/calendars");
    }

    private void fillRegistrationForm(
            Page page,
            String username,
            String displayName,
            String calendarName,
            String password) {
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='displayName']").fill(displayName);
        page.locator("input[id$='calendarName']").fill(calendarName);
        page.locator("input[id$='password']").fill(password);
    }

    private void signIn(Page page, String username, String password) {
        page.navigate(route("/login"));
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='password']").fill(password);
        page.locator("button:has-text('Sign in')").click();
        page.waitForURL("**/app/calendars");
    }

    private void signOut(Page page) {
        page.locator("input[value='Sign out']").click();
    }

    private void fillPasswordChangeForm(
            Page page,
            String currentPassword,
            String newPassword,
            String confirmation) {
        page.locator("input[id$='currentPassword']").fill(currentPassword);
        page.locator("input[id$='newPassword']").fill(newPassword);
        page.locator("input[id$='newPasswordConfirmation']").fill(confirmation);
    }

    private void createCalendar(Page page, String calendarName) {
        page.locator("input[id$='calendarName']").fill(calendarName);
        page.locator("button:has-text('Create calendar')").click();
        assertBodyContains(page, calendarName);
    }

    private void openCalendar(Page page, String calendarName) {
        if (!URI.create(page.url()).getPath().equals("/app/calendars")) {
            page.navigate(route("/app/calendars"));
        }
        page.locator("a", new Page.LocatorOptions().setHasText(calendarName)).first().click();
        page.waitForURL("**/calendar/*");
    }

    private void enterEvent(
            Page page,
            String title,
            String location,
            String startTime,
            String endTime,
            boolean allDay) {
        page.locator("input[id$='eventTitle']").fill(title);
        if (location != null) {
            page.locator("input[id$='eventLocation']").fill(location);
        }
        if (allDay) {
            Locator allDayCheckbox = page.getByLabel("All-day event");
            page.locator(".checkbox-field .ui-chkbox-box").click();
            assertThat(allDayCheckbox).isChecked();
            Locator firstDayInput = page.locator("input[id$='eventStartDate_input']");
            Locator lastDayInput = page.locator("input[id$='eventEndDate_input']");
            assertThat(firstDayInput).isVisible();
            assertThat(lastDayInput).isVisible();
            firstDayInput.fill(startTime.substring(0, 10));
            lastDayInput.fill(endTime.substring(0, 10));
        } else {
            page.locator("input[id$='eventStart_input']").fill(startTime);
            page.locator("input[id$='eventEnd_input']").fill(endTime);
        }
        page.locator("button:has-text('Create event')").click();
    }

    private void enterEventWithDescription(
            Page page,
            String title,
            String description,
            String location,
            String startTime,
            String endTime) {
        page.locator("input[id$='eventTitle']").fill(title);
        page.locator("input[id$='eventLocation']").fill(location);
        page.locator("input[id$='eventStart_input']").fill(startTime);
        page.locator("input[id$='eventEnd_input']").fill(endTime);
        page.locator("textarea[id$='eventDescription']").fill(description);
        page.locator("button:has-text('Create event')").click();
        assertBodyContains(page, title);
    }

    private void assertRegistrationRejected(
            Page page,
            String invitationLink,
            String username,
            String password,
            String expectedMessage) {
        page.navigate(invitationLink);
        fillRegistrationForm(page, username, "Rejected user", "Rejected calendar", password);
        page.locator("button:has-text('Register')").click();
        assertBodyContains(page, expectedMessage);
    }

    private void setPublicAccess(Page page, boolean enabled) {
        Locator publicAccessCheckbox = page.getByLabel("Enable public read-only access");
        if (publicAccessCheckbox.isChecked() != enabled) {
            page.locator(".checkbox-field .ui-chkbox-box").click();
        }
        page.locator("button:has-text('Save settings')").click();
        assertBodyContains(page, enabled ? "Public access enabled" : "Public access disabled");
    }

    private void setRawInputValue(Locator input, String value) {
        input.evaluate(
                "(element, rawValue) => { element.removeAttribute('maxlength'); element.value = rawValue; }",
                value);
    }

    private void clickWithoutChangingFocus(Locator button) {
        button.evaluate("element => element.click()");
    }

    private void scheduleClickAt(Locator button, long epochMilliseconds) {
        button.evaluate(
                "(element, clickTime) => window.setTimeout("
                        + "() => element.click(), Math.max(0, Number(clickTime) - Date.now()))",
                Long.toString(epochMilliseconds));
    }

    private void waitForInvitationAcceptanceResult(Page page) {
        page.waitForFunction(
                "() => location.pathname.startsWith('/calendar/') "
                        + "|| document.body.innerText.includes('Invitation is invalid or no longer available.')");
    }

    private void waitForMembershipChangeResult(Page page) {
        page.waitForFunction(
                "() => document.body.innerText.includes('Member role saved.') "
                        + "|| document.body.innerText.includes('Admin access is required.')");
    }

    private void assertVisibleFocus(Locator locator) {
        locator.focus();
        assertVisibleOutline(locator);
    }

    private void pressTabUntilFocused(Page page, Locator target, int maximumTabPresses) {
        for (int tabPress = 0; tabPress < maximumTabPresses; tabPress++) {
            page.keyboard().press("Tab");
            if (Boolean.TRUE.equals(target.evaluate("element => document.activeElement === element"))) {
                return;
            }
        }
        fail("The target control was not reachable within " + maximumTabPresses + " Tab presses.");
    }

    private void assertVisibleOutline(Locator locator) {
        assertEquals(
                true,
                locator.evaluate(
                        "element => { const style = getComputedStyle(element); "
                                + "return style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0; }"),
                "The focused control should have a visible outline.");
    }

    private void assertHeaderPosition(Page page, int viewportWidth) {
        String position = (String) page.locator(".app-header").evaluate("element => getComputedStyle(element).position");
        assertEquals(viewportWidth <= 820 ? "static" : "sticky", position);
    }

    private void assertResponsiveTableRegion(Page page, String accessibleLabel, int viewportWidth) {
        Locator tableRegion = page.locator(".table-scroll-region[aria-label='" + accessibleLabel + "']");
        tableRegion.focus();
        assertEquals(true, tableRegion.evaluate("element => document.activeElement === element"));
        assertVisibleOutline(tableRegion);
        if (viewportWidth <= 390) {
            assertEquals(
                    true,
                    tableRegion.evaluate("element => element.scrollWidth > element.clientWidth"),
                    accessibleLabel + " should scroll horizontally within its labeled region on small screens.");
        }
    }

    private Page newPage(BrowserContext browserContext, List<String> browserMessages) {
        Page page = browserContext.newPage();
        page.onConsoleMessage(message -> {
            if (message.type().equals("error") || message.type().equals("warning")) {
                browserMessages.add(message.type() + ": " + message.text());
            }
        });
        return page;
    }

    private void waitForApplicationHealth() throws InterruptedException {
        URI healthUri = URI.create(removeTrailingSlashes(applicationBaseUri.toString()) + "/health");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadlineNanos = System.nanoTime() + APPLICATION_READY_TIMEOUT.toNanos();
        String lastHealthCheckResult = "no response";

        while (System.nanoTime() < deadlineNanos) {
            HttpRequest request = HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(5)).GET().build();
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
        seedUser(username, "End-to-end user", true);
    }

    private void seedUser(String username, String displayName, boolean active) throws SQLException {
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
                                + "values (?, ?, ?, ?, now(), now())")) {
            statement.setString(1, username);
            statement.setString(2, displayName);
            statement.setString(3, SEEDED_PASSWORD_HASH_FOR_TEST_PASSWORD);
            statement.setBoolean(4, active);
            statement.executeUpdate();
        }
    }

    private String insertExpiredInvitation(String creatorUsername) throws SQLException {
        String invitationToken = "expired-test-" + UUID.randomUUID().toString().replace("-", "");
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
                        "insert into app_invitation "
                                + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at, created_at) "
                                + "select ?, null, null, id, now() - interval '1 minute', now() "
                                + "from app_user where username = ?")) {
            statement.setString(1, invitationToken);
            statement.setString(2, creatorUsername);
            assertEquals(1, statement.executeUpdate(), "Expected one expired invitation to be inserted for test setup.");
        }
        return route("/register?token=" + invitationToken);
    }

    private void setCalendarActive(long calendarId, boolean active) throws SQLException {
        executeDatabaseUpdate("update calendar set active = ? where id = ?", active, calendarId);
    }

    private void setUserActive(String username, boolean active) throws SQLException {
        executeDatabaseUpdate("update app_user set active = ? where username = ?", active, username);
    }

    private long findCalendarId(String calendarName) throws SQLException {
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
                        "select id from calendar where name = ? order by id desc limit 1")) {
            statement.setString(1, calendarName);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), () -> "Expected calendar '" + calendarName + "' to exist.");
                return resultSet.getLong(1);
            }
        }
    }

    private String calendarLink(String calendarId) throws SQLException {
        long numericCalendarId = Long.parseLong(calendarId);
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
                        "select public_token from calendar where id = ?")) {
            statement.setLong(1, numericCalendarId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), () -> "Expected calendar " + calendarId + " to exist.");
                return route("/calendar/" + resultSet.getString(1));
            }
        }
    }

    private long countActiveAdmins(long calendarId) throws SQLException {
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
                        "select count(*) from calendar_member "
                                + "where calendar_id = ? and role_name = 'ADMIN' and active = true")) {
            statement.setLong(1, calendarId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected the active-admin count query to return one row.");
                return resultSet.getLong(1);
            }
        }
    }

    private void executeDatabaseUpdate(String sql, Object... parameters) throws SQLException {
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
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
                statement.setObject(parameterIndex + 1, parameters[parameterIndex]);
            }
            assertEquals(1, statement.executeUpdate(), "Expected one database record to be updated for test setup.");
        }
    }

    private long queryLong(String sql, Object... parameters) throws SQLException {
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
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
                statement.setObject(parameterIndex + 1, parameters[parameterIndex]);
            }
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected the database query to return one row.");
                return resultSet.getLong(1);
            }
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

    private void assertCanonicalCalendarRoute(Page page, String calendarId) throws SQLException {
        URI currentUri = URI.create(page.url());
        URI expectedUri = URI.create(calendarLink(calendarId));
        assertEquals(expectedUri.getPath(), currentUri.getPath(), () -> "Unexpected calendar URL " + page.url() + ".");
        assertNull(currentUri.getRawQuery(), () -> "The canonical calendar URL must not contain a query string: " + page.url());
        assertTrue(isCanonicalCalendarPath(page.url()), () -> "Expected a token-based calendar URL, but saw " + page.url() + ".");
    }

    private boolean isCanonicalCalendarPath(String url) {
        String path = URI.create(url).getPath();
        return path.startsWith("/calendar/")
                && path.length() > "/calendar/".length()
                && path.indexOf('/', "/calendar/".length()) < 0;
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

    private void assertRollingSessionCookie(Response response) {
        String sessionCookieHeader = response.headerValues("set-cookie").stream()
                .filter(header -> header.contains("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Authenticated activity did not refresh the session cookie."));
        assertTrue(sessionCookieHeader.contains("Path=/"));
        assertTrue(sessionCookieHeader.contains("HttpOnly"));
        assertTrue(sessionCookieHeader.contains("SameSite=Lax"));
        assertTrue(
                sessionCookieHeader.contains("Max-Age=2592000")
                        || sessionCookieHeader.contains("Expires="),
                "Refreshed session cookie did not have a persistent 30-day lifetime.");
    }

    private String requiredSessionCookieValue(BrowserContext browserContext) {
        return browserContext.cookies(route("/")).stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.name))
                .map(cookie -> cookie.value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a JSESSIONID cookie."));
    }

    private void assertOnlyExpectedNotFoundNavigationMessage(List<String> browserMessages) {
        assertTrue(
                browserMessages.size() <= 1
                        && browserMessages.stream().allMatch(message -> message.contains("status of 404")),
                () -> "Expected only the browser's failed-navigation message for the intentional 404, but saw: "
                        + browserMessages);
    }

    private void assertOnlyExpectedNotFoundNavigationMessages(List<String> browserMessages) {
        assertTrue(
                !browserMessages.isEmpty()
                        && browserMessages.stream().allMatch(message -> message.contains("status of 404")),
                () -> "Expected only intentional 404 navigation messages, but saw: " + browserMessages);
    }

    private boolean hasHorizontalOverflow(Page page) {
        return Boolean.TRUE.equals(page.evaluate(
                "() => document.documentElement.scrollWidth > document.documentElement.clientWidth"));
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
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
        return normalizedValue.length() <= 240 ? normalizedValue : normalizedValue.substring(0, 240) + "...";
    }
}
