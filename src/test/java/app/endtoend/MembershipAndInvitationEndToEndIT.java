package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class MembershipAndInvitationEndToEndIT extends SharedCalendarEndToEndSupport {
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
            assertTrue(
                    !registrationInvitationLink.equals(editorInvitationLink),
                    "Registration and editor invitations must use different bearer links.");
            assertTrue(
                    !editorInvitationLink.equals(existingUserInvitationLink),
                    "Each editor invitation must use a unique bearer link.");
            signOut(page);

            navigateToBearerLink(page, registrationInvitationLink);
            Locator signInContinuation = page.locator("a:has-text('Sign in to accept')");
            String signInContinuationLink = signInContinuation.getAttribute("href");
            assertTrue(
                    signInContinuationLink != null && signInContinuationLink.contains("/login?token="),
                    "Expected the sign-in continuation to use the canonical token parameter.");
            assertFalse(signInContinuationLink != null && signInContinuationLink.contains("invite="));
            String expectedInvitationQuery = URI.create(registrationInvitationLink).getRawQuery();
            assertTrue(
                    expectedInvitationQuery != null && expectedInvitationQuery.startsWith("token="),
                    "Expected a token query in the generated invitation link.");
            String invitationToken = expectedInvitationQuery.substring("token=".length());

            signInContinuation.click();
            waitForUrlOrFail(
                    page,
                    "**/login?token=*",
                    "invitation sign-in continuation");
            submitSignInAndWaitForUrl(page, ownerUsername, TEST_PASSWORD, "**/register?token=*");
            assertTrue(
                    expectedInvitationQuery.equals(URI.create(page.url()).getRawQuery()),
                    "The sign-in continuation must preserve the invitation token without logging it.");
            assertBodyContains(page, "Accept invitation");
            signOut(page);

            navigateToBearerLink(page, route("/login?invite=" + invitationToken));
            submitSignInAndWaitForUrl(page, ownerUsername, TEST_PASSWORD, "**/register?token=*");
            assertTrue(
                    expectedInvitationQuery.equals(URI.create(page.url()).getRawQuery()),
                    "The legacy sign-in continuation must normalize and preserve the invitation token.");
            assertBodyContains(page, "Accept invitation");
            signOut(page);

            navigateToBearerLink(page, route("/register"));
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
            assertTrue(
                    !preregistrationSessionIdentifier.equals(requiredSessionCookieValue(browserContext)),
                    "Registration must replace the pre-registration session identifier.");
            assertBodyContains(page, registrationCalendarName);
            assertFalse(
                    page.locator("body").innerText().contains(ownerCalendarName),
                    "A registration invitation must not grant access to the inviter's calendar.");

            navigateToBearerLink(page, existingUserInvitationLink);
            assertEquals("Accept invitation", page.locator("h1").textContent().trim());
            assertBodyContains(page, "already signed in");
            page.locator("button:has-text('Accept invitation')").click();
            waitForCanonicalCalendarRoute(page);
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
            assertBodyContains(page, "Editor");
            assertBodyContains(page, "Admin");

            navigateToBearerLink(page, editorInvitationLink);
            page.locator("button:has-text('Accept invitation')").click();
            assertBodyContains(page, "Invitation is invalid or no longer available.");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void completedInvitationAcceptanceSurvivesAnAmbiguousNetworkResponseWithoutDuplicateMembership()
            throws Exception {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "ambiguous-owner-" + uniqueSuffix;
        String candidateUsername = "ambiguous-candidate-" + uniqueSuffix;
        String calendarName = "Ambiguous response calendar " + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(candidateUsername);
        String invitationLink;
        long calendarId;

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            createCalendar(ownerPage, calendarName);
            calendarId = findCalendarId(calendarName);
            invitationLink = createEditorInvitation(ownerPage, calendarName);
        }

        List<String> browserMessages = new ArrayList<>();
        AtomicBoolean responseWasDiscarded = new AtomicBoolean();
        AtomicInteger serverResponseStatus = new AtomicInteger(-1);
        AtomicReference<Throwable> interceptionFailure = new AtomicReference<>();
        try (BrowserContext candidateContext = browser.newContext()) {
            Page candidatePage = newPage(candidateContext, browserMessages);
            signIn(candidatePage, candidateUsername, TEST_PASSWORD);
            navigateToBearerLink(candidatePage, invitationLink);
            assertThat(candidatePage.locator("button:has-text('Accept invitation')")).isVisible();

            try (AutoCloseable ignored = candidatePage.route("**/register", route -> {
                if (!route.request().method().equals("POST") || responseWasDiscarded.get()) {
                    route.resume();
                    return;
                }

                try {
                    APIResponse serverResponse = route.fetch();
                    try {
                        serverResponseStatus.set(serverResponse.status());
                    } finally {
                        serverResponse.dispose();
                    }
                    responseWasDiscarded.set(true);
                    route.abort("connectionreset");
                } catch (Throwable throwable) {
                    interceptionFailure.set(throwable);
                    try {
                        route.abort("failed");
                    } catch (RuntimeException abortFailure) {
                        throwable.addSuppressed(abortFailure);
                    }
                }
            })) {
                candidatePage
                        .locator("button:has-text('Accept invitation')")
                        .evaluate("button => button.click()");
                candidatePage.waitForCondition(
                        () -> responseWasDiscarded.get() || interceptionFailure.get() != null,
                        new Page.WaitForConditionOptions().setTimeout(10_000));
            }

            if (interceptionFailure.get() != null) {
                fail("Could not simulate the discarded invitation response ("
                        + interceptionFailure.get().getClass().getSimpleName() + ").");
            }
            assertEquals(200, serverResponseStatus.get(), "The server must complete the intercepted request.");
            assertEquals(
                    1L,
                    queryLong(
                            "select count(*) from calendar_member member_record "
                                    + "join app_user on app_user.id = member_record.user_id "
                                    + "where member_record.calendar_id = ? and app_user.username = ? "
                                    + "and member_record.active = true and member_record.role_name = 'EDITOR'",
                            calendarId,
                            candidateUsername),
                    "The completed request must create exactly one active editor membership.");
            assertEquals(
                    1L,
                    queryLong(
                            "select count(*) from app_invitation invitation "
                                    + "join app_user on app_user.id = invitation.accepted_by_user_id "
                                    + "where invitation.calendar_id = ? and app_user.username = ? "
                                    + "and invitation.accepted_at is not null",
                            calendarId,
                            candidateUsername),
                    "The completed request must persist who accepted the invitation and when.");

            navigateToBearerLink(candidatePage, invitationLink);
            candidatePage.locator("button:has-text('Accept invitation')").click();
            assertBodyContains(candidatePage, "Invitation is invalid or no longer available.");
            assertEquals(
                    1L,
                    queryLong(
                            "select count(*) from calendar_member member_record "
                                    + "join app_user on app_user.id = member_record.user_id "
                                    + "where member_record.calendar_id = ? and app_user.username = ? "
                                    + "and member_record.active = true and member_record.role_name = 'EDITOR'",
                            calendarId,
                            candidateUsername),
                    "Retrying after an ambiguous response must not duplicate the accepted membership.");
            navigateToBearerLink(candidatePage, route("/app/calendars"));
            assertBodyContains(candidatePage, calendarName);
            openCalendar(candidatePage, calendarName);
            assertBodyContains(candidatePage, "Editor");
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
            createEvent(page, eventTitle, null, "2026-09-20 10:00", "2026-09-20 12:00", false);
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
            navigateToBearerLink(page, page.url());
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
            navigateToBearerLink(page, page.url());
            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            assertThat(firstMemberRow.locator("select")).hasValue("EDITOR");

            secondMemberRow.locator("button:has-text('Remove access')").click();
            confirmationButton(page, "Remove access").click();
            assertBodyContains(page, "Member access removed.");
            navigateToBearerLink(page, page.url());
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            assertThat(secondMemberRow).containsText("Inactive");
            secondMemberRow.locator("select").selectOption("EDITOR");
            secondMemberRow.locator("button:has-text('Reactivate access')").click();
            assertBodyContains(page, "Member access reactivated.");
            navigateToBearerLink(page, page.url());
            secondMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(secondMemberUsername));
            assertThat(secondMemberRow).containsText("Active");

            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            firstMemberRow.locator("button:has-text('Remove access')").click();
            confirmationButton(page, "Remove access").click();
            assertBodyContains(page, "Member access removed.");
            assertBodyContains(page, "Public access through the calendar link is unchanged.");
            navigateToBearerLink(page, page.url());
            firstMemberRow = page.locator("tr", new Page.LocatorOptions().setHasText(firstMemberUsername));
            assertThat(firstMemberRow).containsText("Inactive");

            List<String> missingIdentifierBrowserMessages = new ArrayList<>();
            Page missingIdentifierPage = newPage(browserContext, missingIdentifierBrowserMessages);
            assertEquals(404, navigateToBearerLink(missingIdentifierPage, route("/app/calendar-members")).status());
            assertBodyContains(missingIdentifierPage, "Calendar not found");
            assertFalse(missingIdentifierPage.locator("body").innerText().contains("Exception thrown"));
            assertOnlyExpectedNotFoundNavigationMessage(missingIdentifierBrowserMessages);
            missingIdentifierPage.close();

            signOut(page);
            signIn(page, firstMemberUsername, password);
            assertFalse(page.locator("body").innerText().contains(calendarName));
            navigateToBearerLink(page, sharedCalendarLink);
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
            createEvent(setupPage, eventTitle, null, "2026-10-01 10:00", "2026-10-01 11:00", false);
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
                navigateToBearerLink(unauthenticatedPage, route(protectedRoute));
                assertEquals("/login", URI.create(unauthenticatedPage.url()).getPath());
                assertBodyContains(unauthenticatedPage, "Sign in");
            }
            assertEquals(200, navigateToBearerLink(unauthenticatedPage, calendarLink).status());
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
            assertBodyContains(editorPage, "Editor");
            assertThat(editorPage.locator("button:has-text('Create event')")).isVisible();
            createEvent(
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
            clickAndWaitForNavigation(
                    editorPage,
                    confirmationButton(editorPage, "Regenerate link"),
                    "editor calendar-link regeneration");
            calendarLink = editorPage.url();
            assertTrue(
                    !editorOriginalLink.equals(calendarLink),
                    "An editor regeneration must replace the previous calendar bearer link.");
            navigateToBearerLink(editorPage, route("/app/calendar-settings?id=" + calendarId));
            assertBodyContains(editorPage, "Calendar not found");
            navigateToBearerLink(editorPage, route("/app/calendar-members?id=" + calendarId));
            assertBodyContains(editorPage, "Calendar not found");
            assertOnlyExpectedNotFoundNavigationMessages(browserMessages);
        }

        try (BrowserContext unrelatedContext = browser.newContext()) {
            List<String> browserMessages = new ArrayList<>();
            Page unrelatedPage = newPage(unrelatedContext, browserMessages);
            signIn(unrelatedPage, unrelatedUsername, TEST_PASSWORD);
            assertFalse(unrelatedPage.locator("body").innerText().contains(calendarName));
            navigateToBearerLink(unrelatedPage, calendarLink);
            assertBodyContains(unrelatedPage, eventTitle);
            assertBodyContains(unrelatedPage, "Read-only");
            assertEquals(0, unrelatedPage.locator("button:has-text('Create event')").count());
            assertEquals(0, unrelatedPage.locator("button:has-text('Edit')").count());
            assertEquals(0, unrelatedPage.locator("button:has-text('Delete')").count());
            for (String inaccessibleRoute : List.of(
                    "/app/calendar-settings?id=" + calendarId,
                    "/app/calendar-members?id=" + calendarId)) {
                navigateToBearerLink(unrelatedPage, route(inaccessibleRoute));
                assertBodyContains(unrelatedPage, "Calendar not found");
            }
            navigateToBearerLink(unrelatedPage, route("/app/invitations"));
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
        String acceptedInvitationLink;
        String inactiveCreatorInvitationLink;
        assertInvitationLifetimeConstraintRejectsLongerInvitation(ownerUsername);

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            availableInvitationLink = createRegistrationInvitation(ownerPage);
            assertEquals(
                    Duration.ofDays(7).toSeconds(),
                    queryLong(
                            "select extract(epoch from (expires_at - created_at))::bigint "
                                    + "from app_invitation where created_by_user_id = "
                                    + "(select id from app_user where username = ?) "
                                    + "order by id desc limit 1",
                            ownerUsername));
            revokedInvitationLink = createRegistrationInvitation(ownerPage);
            invitationRow(ownerPage, revokedInvitationLink).locator("button:has-text('Revoke')").click();
            confirmationButton(ownerPage, "Revoke invitation").click();
            assertBodyContains(ownerPage, "Invitation revoked.");
            assertThat(invitationRow(ownerPage, revokedInvitationLink)).containsText("Revoked");
            assertEquals(0, invitationRow(ownerPage, revokedInvitationLink).locator("button:has-text('Revoke')").count());

            expiredInvitationLink = insertExpiredInvitation(ownerUsername);
            navigateToBearerLink(ownerPage, route("/app/invitations"));
            assertThat(invitationRow(ownerPage, expiredInvitationLink)).containsText("Expired");
            assertEquals(0, invitationRow(ownerPage, expiredInvitationLink).locator("button:has-text('Revoke')").count());
            assertThat(invitationRow(ownerPage, availableInvitationLink)).containsText("Available");
            assertThat(invitationRow(ownerPage, availableInvitationLink).locator("button:has-text('Revoke')")).isVisible();

            acceptedInvitationLink = createRegistrationInvitation(ownerPage);
            try (BrowserContext registrationContext = browser.newContext()) {
                Page registrationPage = registrationContext.newPage();
                registerNewUser(
                        registrationPage,
                        acceptedInvitationLink,
                        "status-accepted-" + uniqueSuffix,
                        "Accepted invitation " + uniqueSuffix,
                        "Accepted calendar " + uniqueSuffix,
                        password);
            }
            navigateToBearerLink(ownerPage, ownerPage.url());
            assertThat(invitationRow(ownerPage, acceptedInvitationLink)).containsText("Accepted");
            assertEquals(0, invitationRow(ownerPage, acceptedInvitationLink).locator("button:has-text('Revoke')").count());
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
    void invitationHistoryUsesDatabaseBackedPaginationWithoutHidingOlderAvailableInvitations() throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String username = "pagination-owner-" + uniqueSuffix;
        seedUser(username);
        insertRegistrationInvitations(username, 55);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            signIn(page, username, TEST_PASSWORD);
            navigateToBearerLink(page, route("/app/invitations"));

            Locator invitationRows = page.locator(".ui-datatable tbody tr");
            assertThat(invitationRows).hasCount(50);
            assertThat(page.locator(".ui-paginator-next")).isEnabled();
            page.locator(".ui-paginator-next").click();

            assertThat(invitationRows).hasCount(5);
            assertEquals(5, invitationRows.locator("button:has-text('Revoke')").count());
            assertThat(page.locator(".ui-paginator-prev")).isEnabled();
            assertNoBrowserMessages(browserMessages);
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
            navigateToBearerLink(page, route("/app/calendar-members?id=" + calendarId));
            Locator memberRow = page.locator("tr", new Page.LocatorOptions().setHasText(memberUsername));
            memberRow.locator("select").selectOption("ADMIN");
            memberRow.locator("button:has-text('Save role')").click();
            assertBodyContains(page, "Member role saved.");
            String strongerRoleInvitationLink = createEditorInvitation(page, calendarName);
            signOut(page);

            signIn(page, memberUsername, password);
            navigateToBearerLink(page, strongerRoleInvitationLink);
            page.locator("button:has-text('Accept invitation')").click();
            waitForCanonicalCalendarRoute(page);
            assertBodyContains(page, "Admin");
            signOut(page);

            signIn(page, ownerUsername, TEST_PASSWORD);
            navigateToBearerLink(page, route("/app/calendar-members?id=" + calendarId));
            memberRow = page.locator("tr", new Page.LocatorOptions().setHasText(memberUsername));
            memberRow.locator("button:has-text('Remove access')").click();
            confirmationButton(page, "Remove access").click();
            assertBodyContains(page, "Member access removed.");
            String reactivationInvitationLink = createEditorInvitation(page, calendarName);
            signOut(page);

            signIn(page, memberUsername, password);
            navigateToBearerLink(page, reactivationInvitationLink);
            page.locator("button:has-text('Accept invitation')").click();
            waitForCanonicalCalendarRoute(page);
            assertBodyContains(page, calendarName);
            assertBodyContains(page, "Editor");
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
            navigateToBearerLink(editorPage, membershipInvitationLink);
            editorPage.locator("button:has-text('Accept invitation')").click();
            waitForCanonicalCalendarRoute(editorPage);
            String savedSelfInvitationLink = createEditorInvitation(editorPage, calendarName);
            String administratorRevocationLink = createEditorInvitation(editorPage, calendarName);

            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            navigateToBearerLink(ownerPage, route("/app/calendar-members?id=" + calendarId));
            Locator editorRow = ownerPage.locator("tr", new Page.LocatorOptions().setHasText(editorUsername));
            editorRow.locator("button:has-text('Remove access')").click();
            confirmationButton(ownerPage, "Remove access").click();
            assertBodyContains(ownerPage, "Member access removed.");

            navigateToBearerLink(ownerPage, route("/app/invitations"));
            assertThat(invitationRow(ownerPage, savedSelfInvitationLink)).isVisible();
            Locator administratorRevocationRow = invitationRow(ownerPage, administratorRevocationLink);
            assertThat(administratorRevocationRow).isVisible();
            administratorRevocationRow.locator("button:has-text('Revoke')").click();
            clickAndWaitForNavigation(
                    ownerPage,
                    confirmationButton(ownerPage, "Revoke invitation"),
                    "invitation revocation");
            assertBodyContains(ownerPage, "Invitation revoked.");
            assertThat(invitationRow(ownerPage, administratorRevocationLink)).containsText("Revoked");

            navigateToBearerLink(editorPage, savedSelfInvitationLink);
            editorPage.locator("button:has-text('Accept invitation')").click();
            assertBodyContains(editorPage, "Invitation is invalid or no longer available.");
            navigateToBearerLink(editorPage, calendarLink(calendarId));
            assertBodyContains(editorPage, calendarName);
            assertBodyContains(editorPage, "Read-only");
            assertEquals(0, editorPage.locator("button:has-text('Create event')").count());
        }
    }

    @Test
    void concurrentDistinctInvitationsForOneUserCreateOnlyOneMembershipWithoutFailure() throws Exception {
        String uniqueSuffix = uniqueSuffix();
        String ownerUsername = "distinct-invite-owner-" + uniqueSuffix;
        String candidateUsername = "distinct-invite-candidate-" + uniqueSuffix;
        String calendarName = "Distinct invitations " + uniqueSuffix;
        seedUser(ownerUsername);
        seedUser(candidateUsername);
        String firstInvitationLink;
        String secondInvitationLink;
        long calendarId;

        try (BrowserContext ownerContext = browser.newContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            createCalendar(ownerPage, calendarName);
            calendarId = findCalendarId(calendarName);
            firstInvitationLink = createEditorInvitation(ownerPage, calendarName);
            secondInvitationLink = createEditorInvitation(ownerPage, calendarName);
        }

        try (BrowserContext firstContext = browser.newContext(); BrowserContext secondContext = browser.newContext()) {
            Page firstPage = firstContext.newPage();
            Page secondPage = secondContext.newPage();
            signIn(firstPage, candidateUsername, TEST_PASSWORD);
            signIn(secondPage, candidateUsername, TEST_PASSWORD);
            navigateToBearerLink(firstPage, firstInvitationLink);
            navigateToBearerLink(secondPage, secondInvitationLink);
            Locator firstAcceptButton = firstPage.locator("button:has-text('Accept invitation')");
            Locator secondAcceptButton = secondPage.locator("button:has-text('Accept invitation')");
            assertThat(firstAcceptButton).isVisible();
            assertThat(secondAcceptButton).isVisible();

            try (Connection blockingConnection = lockCalendarRowForConcurrentRequests(calendarId)) {
                clickWithoutChangingFocus(firstAcceptButton);
                clickWithoutChangingFocus(secondAcceptButton);
                waitForBlockedDatabaseRequests(
                        blockingConnection,
                        2,
                        "distinct invitation acceptance");
                blockingConnection.commit();
            }
            waitForInvitationAcceptanceResult(firstPage);
            waitForInvitationAcceptanceResult(secondPage);

            assertTrue(isCanonicalCalendarPath(firstPage.url()));
            assertTrue(isCanonicalCalendarPath(secondPage.url()));
            assertBodyContains(firstPage, calendarName);
            assertBodyContains(secondPage, calendarName);
            assertBodyContains(firstPage, "Editor");
            assertBodyContains(secondPage, "Editor");
            assertEquals(
                    1L,
                    queryLong(
                            "select count(*) from calendar_member "
                                    + "where calendar_id = ? and user_id = "
                                    + "(select id from app_user where username = ?)",
                            calendarId,
                            candidateUsername));
        }
    }

    @Test
    void concurrentInvitationAcceptanceAllowsExactlyOneUserAndRemovedEditorsLoseMutationAccess()
            throws Exception {
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
            navigateToBearerLink(firstPage, invitationLink);
            navigateToBearerLink(secondPage, invitationLink);
            Locator firstAcceptButton = firstPage.locator("button:has-text('Accept invitation')");
            Locator secondAcceptButton = secondPage.locator("button:has-text('Accept invitation')");
            assertThat(firstAcceptButton).isVisible();
            assertThat(secondAcceptButton).isVisible();

            try (Connection blockingConnection = lockInvitationRowForConcurrentRequests(invitationLink)) {
                clickWithoutChangingFocus(firstAcceptButton);
                clickWithoutChangingFocus(secondAcceptButton);
                waitForBlockedDatabaseRequests(
                        blockingConnection,
                        2,
                        "single-use invitation acceptance");
                blockingConnection.commit();
            }
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
            assertBodyContains(acceptedPage, "Editor");
            assertBodyContains(rejectedPage, "Invitation is invalid or no longer available.");

            navigateToBearerLink(rejectedPage, route("/app/invitations"));
            assertFalse(
                    invitationIsListed(rejectedPage, invitationLink),
                    "Another user's invitation must not be exposed for revocation.");

            signIn(ownerPage, ownerUsername, TEST_PASSWORD);
            navigateToBearerLink(ownerPage, route("/app/calendar-members?id=" + calendarId));
            Locator acceptedMemberRow = ownerPage.locator("tr", new Page.LocatorOptions().setHasText(acceptedUsername));
            assertThat(acceptedMemberRow).containsText("Active");
            assertEquals(
                    0,
                    ownerPage.locator("tr", new Page.LocatorOptions().setHasText(rejectedUsername)).count(),
                    "The losing contender must not receive membership.");
            acceptedMemberRow.locator("button:has-text('Remove access')").click();
            confirmationButton(ownerPage, "Remove access").click();
            assertBodyContains(ownerPage, "Member access removed.");

            navigateToBearerLink(acceptedPage, acceptedPage.url());
            assertBodyContains(acceptedPage, calendarName);
            assertBodyContains(acceptedPage, "Read-only");
            assertEquals(0, acceptedPage.locator("button:has-text('Create event')").count());
        }
    }

    @Test
    void concurrentAdministratorsCannotDemoteEachOtherAndLeaveTheCalendarWithoutAnAdmin() throws Exception {
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
            navigateToBearerLink(setupPage, route("/app/calendar-members?id=" + calendarId));
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
            navigateToBearerLink(ownerPage, route("/app/calendar-members?id=" + calendarId));
            navigateToBearerLink(secondAdminPage, route("/app/calendar-members?id=" + calendarId));

            Locator ownerTargetingSecondAdmin = ownerPage.locator(
                    "tr", new Page.LocatorOptions().setHasText(secondAdminUsername));
            Locator secondAdminTargetingOwner = secondAdminPage.locator(
                    "tr", new Page.LocatorOptions().setHasText(ownerUsername));
            ownerTargetingSecondAdmin.locator("select").selectOption("EDITOR");
            secondAdminTargetingOwner.locator("select").selectOption("EDITOR");

            try (Connection blockingConnection =
                    lockCalendarRowForConcurrentRequests(Long.parseLong(calendarId))) {
                clickWithoutChangingFocus(
                        ownerTargetingSecondAdmin.locator("button:has-text('Save role')"));
                clickWithoutChangingFocus(
                        secondAdminTargetingOwner.locator("button:has-text('Save role')"));
                waitForBlockedDatabaseRequests(
                        blockingConnection,
                        2,
                        "concurrent administrator demotion");
                blockingConnection.commit();
            }
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

            navigateToBearerLink(survivingAdminPage, route("/app/calendar-members?id=" + calendarId));
            assertBodyContains(survivingAdminPage, calendarName);
            navigateToBearerLink(demotedAdminPage, route("/app/calendar-members?id=" + calendarId));
            assertBodyContains(demotedAdminPage, "Calendar not found");
            navigateToBearerLink(demotedAdminPage, calendarLink(calendarId));
            assertBodyContains(demotedAdminPage, calendarName);
            assertBodyContains(demotedAdminPage, "Editor");
            assertThat(demotedAdminPage.locator("button:has-text('Create event')")).isVisible();
        }
    }

}
