package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(OrderAnnotation.class)
class ApplicationEndToEndIT extends SharedCalendarEndToEndSupport {
    @Test
    @Order(1)
    void freshSchemaPublicPagesAndAccessibilityAreSound() throws SQLException {
        assertEquals(
                "app_user,calendar,calendar_event,calendar_membership,"
                        + "flyway_schema_history,invitation,registration_bootstrap",
                queryText(
                        "select string_agg(table_name, ',' order by table_name) "
                                + "from information_schema.tables "
                                + "where table_schema = 'public'"));

        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.setViewportSize(320, 720);
            Response homeResponse = navigate(page, "/");
            assertEquals(200, homeResponse.status());
            assertThat(page.locator("h1")).hasText("Shared event calendars for real plans");
            assertSecurityHeaders(homeResponse);
            assertFalse(Boolean.TRUE.equals(page.evaluate(
                    "() => document.documentElement.scrollWidth "
                            + "> document.documentElement.clientWidth")));
            assertAccessible(page);

            page.keyboard().press("Tab");
            assertThat(page.locator(".skip-link:focus")).hasText("Skip to main content");
            page.keyboard().press("Enter");
            assertEquals("main-content", page.evaluate("document.activeElement.id"));

            navigate(page, "/sign-in");
            assertAccessible(page);
            navigate(page, "/app/calendars");
            page.waitForURL("**/sign-in");
        }
    }

    @Test
    @Order(2)
    void bootstrapRegistrationIsConsumedByExactlyOneConcurrentRequest() throws Exception {
        String suffix = uniqueSuffix();
        CountDownLatch formsReady = new CountDownLatch(2);
        CountDownLatch submitForms = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> firstResult = executor.submit(() -> attemptBootstrapRegistration(
                    "bootstrap-" + suffix + "-one",
                    "Bootstrap one",
                    "Bootstrap calendar one",
                    formsReady,
                    submitForms));
            Future<String> secondResult = executor.submit(() -> attemptBootstrapRegistration(
                    "bootstrap-" + suffix + "-two",
                    "Bootstrap two",
                    "Bootstrap calendar two",
                    formsReady,
                    submitForms));

            assertTrue(formsReady.await(30, TimeUnit.SECONDS), "Both registration forms should become ready.");
            submitForms.countDown();
            List<String> resultingPaths =
                    List.of(firstResult.get(90, TimeUnit.SECONDS), secondResult.get(90, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    resultingPaths.stream().filter("/app/calendars"::equals).count(),
                    "Exactly one concurrent bootstrap request should register.");
        }

        assertEquals(
                1,
                queryLong(
                        "select count(*) from app_user where username like ?",
                        "bootstrap-" + suffix + "-%"));
        assertEquals(
                1,
                queryLong(
                        "select count(*) from registration_bootstrap "
                                + "where singleton_id = 1 and consumed_at is not null"));
    }

    @Test
    @Order(3)
    void authenticationInvitedRegistrationAndPasswordChangeWork() throws SQLException {
        String suffix = uniqueSuffix();
        String inviterUsername = "inviter-" + suffix;
        long inviterId = seedUser(inviterUsername, "Inviter " + suffix);
        seedCalendar(inviterId, "Invitation calendar " + suffix);

        try (BrowserContext inviterContext = newBrowserContext()) {
            Page inviterPage = inviterContext.newPage();
            navigate(inviterPage, "/sign-in");
            inviterPage.locator("input[id$='username']").fill(inviterUsername);
            inviterPage.locator("input[id$='password']").fill("definitely wrong");
            inviterPage.locator("input[type='submit'][value='Sign in']").click();
            assertThat(inviterPage.locator("body")).containsText("Sign-in failed.");
            assertAccessible(inviterPage);

            inviterPage.locator("input[id$='password']").fill(TEST_PASSWORD);
            inviterPage.locator("input[type='submit'][value='Sign in']").click();
            inviterPage.waitForURL("**/app/calendars");
            Cookie authenticationCookie = inviterContext.cookies(route("/")).stream()
                    .filter(cookie -> "LtpaToken2".equals(cookie.name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Authentication cookie was not issued."));
            assertTrue(Boolean.TRUE.equals(authenticationCookie.secure));
            assertTrue(Boolean.TRUE.equals(authenticationCookie.httpOnly));
            assertEquals(SameSiteAttribute.LAX, authenticationCookie.sameSite);

            String invitationLink = createRegistrationInvitation(inviterPage);
            assertThat(inviterPage.locator("body")).containsText("Registration invitation");
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from invitation "
                                    + "where created_by_user_id = ? and calendar_id is null",
                            inviterId));

            String registeredUsername = "friend-" + suffix;
            String changedPassword = "another correct horse battery staple " + suffix;
            try (BrowserContext registeredContext = newBrowserContext();
                    BrowserContext secondRegisteredContext = newBrowserContext()) {
                Page registeredPage = registeredContext.newPage();
                navigateBearerLink(registeredPage, invitationLink);
                assertAccessible(registeredPage);
                fillRegistration(
                        registeredPage,
                        registeredUsername,
                        "Friend " + suffix,
                        "Friend calendar " + suffix,
                        TEST_PASSWORD);
                registeredPage.locator("button:has-text('Register')").click();
                registeredPage.waitForURL("**/app/calendars");

                assertEquals(
                        1,
                        queryLong(
                                "select count(*) from app_user where username = ?",
                                registeredUsername));
                assertEquals(
                        1,
                        queryLong(
                                "select count(*) from calendar_membership membership "
                                        + "join app_user application_user "
                                        + "on application_user.id = membership.user_id "
                                        + "where application_user.username = ? "
                                        + "and membership.role_name = 'ADMIN'",
                                registeredUsername));
                assertEquals(
                        0,
                        queryLong(
                                "select count(*) from invitation "
                                        + "where created_by_user_id = ? and calendar_id is null",
                                inviterId));

                Page secondRegisteredPage = secondRegisteredContext.newPage();
                signIn(secondRegisteredPage, registeredUsername, TEST_PASSWORD);
                assertResponsiveAndAccessible(secondRegisteredPage, 320, 720);

                Response accountSettingsResponse = navigate(registeredPage, "/app/account-settings");
                assertSecurityHeaders(accountSettingsResponse);
                assertResponsiveAndAccessible(registeredPage, 768, 900);
                registeredPage.locator("input[id$='currentPassword']").fill(TEST_PASSWORD);
                registeredPage.locator("input[id$='newPassword']").fill(changedPassword);
                registeredPage
                        .locator("input[id$='newPasswordConfirmation']")
                        .fill(changedPassword);
                registeredPage.locator("button:has-text('Change password')").click();
                registeredPage.waitForURL("**/sign-in**");
                assertThat(registeredPage.locator("body"))
                        .containsText("Your password was changed.");

                navigate(secondRegisteredPage, "/app/calendars");
                secondRegisteredPage.waitForURL("**/sign-in");

                registeredPage.locator("input[id$='username']").fill(registeredUsername);
                registeredPage.locator("input[id$='password']").fill(TEST_PASSWORD);
                registeredPage.locator("input[type='submit'][value='Sign in']").click();
                assertThat(registeredPage.locator("body")).containsText("Sign-in failed.");

                registeredPage.locator("input[id$='password']").fill(changedPassword);
                registeredPage.locator("input[type='submit'][value='Sign in']").click();
                registeredPage.waitForURL("**/app/calendars");
            }
        }
    }

    @Test
    @Order(4)
    void calendarEventsPublicAccessAndLinkRegenerationWork() throws SQLException {
        String suffix = uniqueSuffix();
        String username = "calendar-owner-" + suffix;
        long userId = seedUser(username, "Calendar owner " + suffix);
        seedCalendar(userId, "Seed calendar " + suffix);
        String createdCalendarName = "River plans " + suffix;
        String eventTitle = "Kayaking day " + suffix;
        String allDayEventTitle = "Kayaking weekend " + suffix;

        try (BrowserContext ownerContext = newBrowserContext();
                BrowserContext readerContext = newBrowserContext();
                BrowserContext staleOwnerContext = newBrowserContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, username, TEST_PASSWORD);
            ownerPage.locator("input[id$='calendarName']").fill(createdCalendarName);
            ownerPage.locator("button:has-text('Create calendar')").click();
            assertThat(ownerPage.locator("body")).containsText(createdCalendarName);

            long calendarId =
                    queryLong("select id from calendar where name = ?", createdCalendarName);
            String originalCalendarLinkToken =
                    queryText("select calendar_link_token from calendar where id = ?", calendarId);
            navigate(ownerPage, "/" + originalCalendarLinkToken);
            assertResponsiveAndAccessible(ownerPage, 320, 900);

            ownerPage.locator("button:has-text('Create event')").click();
            assertThat(ownerPage.locator("body")).containsText("Event title is required.");
            ownerPage.waitForFunction(
                    "() => document.activeElement && document.activeElement.id.endsWith('messages')");

            createTimedEvent(
                    ownerPage,
                    eventTitle,
                    "River bank",
                    "2026-08-22 10:00",
                    "2026-08-22 13:00");

            Locator eventCard = ownerPage.locator("article.event-item").filter(
                    new Locator.FilterOptions().setHasText(eventTitle));
            eventCard.locator("button:has-text('Edit')").click();
            assertThat(ownerPage.locator("button:has-text('Save changes')")).isVisible();
            String updatedEventTitle = eventTitle + " updated";
            ownerPage.locator("input[id$='eventTitle']").fill(updatedEventTitle);
            ownerPage.locator("button:has-text('Save changes')").click();
            assertThat(ownerPage.locator("body")).containsText("Event updated.");
            assertThat(ownerPage.locator("article.event-item").filter(
                            new Locator.FilterOptions().setHasText(updatedEventTitle)))
                    .isVisible();

            ownerPage.locator(".event-editor .ui-chkbox-box").click();
            ownerPage.locator("input[id$='eventTitle']").fill(allDayEventTitle);
            ownerPage.locator("input[id$='eventFirstDay_input']").fill("2026-08-23");
            ownerPage.locator("input[id$='eventLastDay_input']").fill("2026-08-24");
            ownerPage.locator("button:has-text('Create event')").click();
            assertThat(ownerPage.locator("article.event-item").filter(
                            new Locator.FilterOptions().setHasText(allDayEventTitle)))
                    .containsText("All day from Sun, Aug 23, 2026 to Mon, Aug 24, 2026");
            String originalAllDayStart = queryText(
                    "select start_at::text from calendar_event where calendar_id = ? and title = ?",
                    calendarId,
                    allDayEventTitle);

            Page readerPage = readerContext.newPage();
            Response publicResponse = navigate(readerPage, "/" + originalCalendarLinkToken);
            assertEquals(200, publicResponse.status());
            assertEquals("noindex, nofollow", publicResponse.headerValue("x-robots-tag"));
            assertThat(readerPage.locator("body")).containsText(updatedEventTitle);
            assertThat(readerPage.locator("body")).containsText("Read-only");
            assertEquals(0, readerPage.locator("button:has-text('Create event')").count());

            navigate(ownerPage, "/app/calendar-settings?id=" + calendarId);
            assertThat(ownerPage.locator(".app-nav a[aria-current='page']")).hasText("My calendars");
            assertResponsiveAndAccessible(ownerPage, 768, 900);
            Page staleOwnerPage = staleOwnerContext.newPage();
            signIn(staleOwnerPage, username, TEST_PASSWORD);
            navigate(staleOwnerPage, "/app/calendar-settings?id=" + calendarId);
            ownerPage.locator("input[id$='timeZone']").fill("America/New_York");
            Locator publicAccessControl = ownerPage.locator(".checkbox-field .ui-chkbox-box");
            publicAccessControl.click();
            ownerPage.locator("button:has-text('Save settings')").click();
            assertThat(ownerPage.locator("body")).containsText("Public access disabled");
            assertFalse(
                    originalAllDayStart.equals(queryText(
                            "select start_at::text from calendar_event where calendar_id = ? and title = ?",
                            calendarId,
                            allDayEventTitle)),
                    "Changing the calendar time zone should move stored all-day boundaries.");

            ownerPage.setViewportSize(320, 900);
            ownerPage.locator("button:has-text('Copy calendar link')").click();
            ownerPage.waitForFunction(
                    "() => document.querySelector('.copy-status').textContent.trim().length > 0");

            staleOwnerPage.locator("textarea[id$='calendarDescription']")
                    .fill("A stale settings update");
            staleOwnerPage.locator("button:has-text('Save settings')").click();
            assertThat(staleOwnerPage.locator("body"))
                    .containsText("This calendar changed after you opened it.");
            assertEquals(
                    "America/New_York",
                    staleOwnerPage.locator("input[id$='timeZone']").inputValue());

            Response disabledResponse = readerPage.reload();
            assertEquals(404, disabledResponse.status());
            assertThat(readerPage.locator("body")).containsText("Calendar link unavailable");

            publicAccessControl.click();
            ownerPage.locator("button:has-text('Save settings')").click();
            assertThat(ownerPage.locator("body")).containsText("Public access enabled");
            assertEquals(200, readerPage.reload().status());

            navigate(ownerPage, "/" + originalCalendarLinkToken);
            assertThat(ownerPage.locator("article.event-item").filter(
                            new Locator.FilterOptions().setHasText(allDayEventTitle)))
                    .containsText("All day from Sun, Aug 23, 2026 to Mon, Aug 24, 2026");
            String originalLink = ownerPage.url();
            ownerPage.locator("button:has-text('Regenerate link')").click();
            assertThat(ownerPage.locator(".ui-confirmdialog-no")).isFocused();
            assertAccessible(ownerPage);
            ownerPage.locator(".ui-confirmdialog-yes").click();
            ownerPage.waitForLoadState();
            assertFalse(
                    originalLink.equals(ownerPage.url()),
                    "Regenerating a calendar link must change its URL.");

            navigateBearerLink(readerPage, originalLink);
            assertThat(readerPage.locator("body")).containsText("Calendar link unavailable");
            navigateBearerLink(readerPage, ownerPage.url());
            assertThat(readerPage.locator("body")).containsText(updatedEventTitle);
        }
    }

    @Test
    @Order(5)
    void editorInvitationAndLastAdminProtectionWork() throws Exception {
        String suffix = uniqueSuffix();
        String adminUsername = "admin-" + suffix;
        String firstEditorUsername = "editor-" + suffix + "-one";
        String secondEditorUsername = "editor-" + suffix + "-two";
        String adminDisplayName = "Admin " + suffix;
        String firstEditorDisplayName = "Editor one " + suffix;
        String secondEditorDisplayName = "Editor two " + suffix;
        long adminId = seedUser(adminUsername, adminDisplayName);
        seedUser(firstEditorUsername, firstEditorDisplayName);
        seedUser(secondEditorUsername, secondEditorDisplayName);
        SeededCalendar calendar = seedCalendar(
                adminId,
                "Membership calendar " + suffix);

        try (BrowserContext adminContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            signIn(adminPage, adminUsername, TEST_PASSWORD);
            String invitationLink = createEditorInvitation(
                    adminPage, "Membership calendar " + suffix);

            CountDownLatch formsReady = new CountDownLatch(2);
            CountDownLatch submitForms = new CountDownLatch(1);
            List<InvitationAcceptanceResult> acceptanceResults;
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<InvitationAcceptanceResult> firstAcceptance = executor.submit(
                        () -> attemptEditorInvitationAcceptance(
                                firstEditorUsername,
                                invitationLink,
                                formsReady,
                                submitForms));
                Future<InvitationAcceptanceResult> secondAcceptance = executor.submit(
                        () -> attemptEditorInvitationAcceptance(
                                secondEditorUsername,
                                invitationLink,
                                formsReady,
                                submitForms));

                assertTrue(
                        formsReady.await(30, TimeUnit.SECONDS),
                        "Both editor invitation forms should become ready.");
                submitForms.countDown();
                acceptanceResults = List.of(
                        firstAcceptance.get(90, TimeUnit.SECONDS),
                        secondAcceptance.get(90, TimeUnit.SECONDS));
            }

            String acceptedCalendarPath = "/" + calendar.calendarLinkToken();
            assertEquals(
                    1,
                    acceptanceResults.stream()
                            .filter(result -> acceptedCalendarPath.equals(result.resultingPath()))
                            .count(),
                    "Exactly one concurrent request should consume the editor invitation.");
            assertEquals(
                    1,
                    acceptanceResults.stream()
                            .filter(result -> "/register".equals(result.resultingPath())
                                    && result.pageText().contains("Invitation could not be accepted."))
                            .count(),
                    "The request that loses the invitation race should receive a clear rejection.");

            String editorUsername = acceptanceResults.stream()
                    .filter(result -> acceptedCalendarPath.equals(result.resultingPath()))
                    .map(InvitationAcceptanceResult::username)
                    .findFirst()
                    .orElseThrow();
            String editorDisplayName = editorUsername.equals(firstEditorUsername)
                    ? firstEditorDisplayName
                    : secondEditorDisplayName;
            assertEquals(
                    "EDITOR",
                    queryText(
                            "select role_name from calendar_membership membership "
                                    + "join app_user application_user "
                                    + "on application_user.id = membership.user_id "
                                    + "where membership.calendar_id = ? "
                                    + "and application_user.username = ?",
                            calendar.id(),
                            editorUsername));
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from calendar_membership membership "
                                    + "join app_user application_user "
                                    + "on application_user.id = membership.user_id "
                                    + "where membership.calendar_id = ? "
                                    + "and application_user.username in (?, ?)",
                            calendar.id(),
                            firstEditorUsername,
                            secondEditorUsername));
            assertEquals(
                    0,
                    queryLong("select count(*) from invitation where calendar_id = ?", calendar.id()));

            try (BrowserContext editorContext = newBrowserContext()) {
                Page editorPage = editorContext.newPage();
                signIn(editorPage, editorUsername, TEST_PASSWORD);
                Response settingsResponse = navigate(
                        editorPage, "/app/calendar-settings?id=" + calendar.id());
                assertEquals(404, settingsResponse.status());
                assertThat(editorPage.locator("body")).containsText("Calendar not found");
                Response membersResponse = navigate(
                        editorPage, "/app/calendar-members?id=" + calendar.id());
                assertEquals(404, membersResponse.status());
                assertThat(editorPage.locator("body")).containsText("Calendar not found");
            }

            navigate(adminPage, "/app/calendar-members?id=" + calendar.id());
            assertResponsiveAndAccessible(adminPage, 320, 900);
            Locator adminCard = memberCard(adminPage, adminDisplayName);
            adminCard.locator("button:has-text('Make editor')").click();
            assertThat(adminPage.locator("body"))
                    .containsText("A calendar must keep at least one admin.");
            assertEquals(
                    "ADMIN",
                    membershipRole(calendar.id(), adminUsername));

            adminCard.locator("button:has-text('Remove access')").click();
            adminPage.locator(".ui-confirmdialog-yes").click();
            assertThat(adminPage.locator("body"))
                    .containsText("A calendar must keep at least one admin.");
            assertEquals("ADMIN", membershipRole(calendar.id(), adminUsername));

            Locator editorCard = memberCard(adminPage, editorDisplayName);
            Response saveEditorRoleResponse = adminPage.waitForResponse(
                    response -> "POST".equals(response.request().method()),
                    () -> editorCard.locator("button:has-text('Make admin')").click());
            assertTrue(saveEditorRoleResponse.ok());
            assertThat(adminPage.locator("body")).containsText("Member role saved.");
            assertThat(memberCard(adminPage, editorDisplayName).locator(".member-role-label"))
                    .hasText("Admin");
            assertEquals("ADMIN", membershipRole(calendar.id(), editorUsername));

            adminCard = memberCard(adminPage, adminDisplayName);
            adminCard.locator("button:has-text('Make editor')").click();
            assertThat(adminPage.locator("body")).containsText("Calendar not found");
            assertEquals("EDITOR", membershipRole(calendar.id(), adminUsername));
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from calendar_membership "
                                    + "where calendar_id = ? and role_name = 'ADMIN'",
                            calendar.id()));
        }
    }

    @Test
    @Order(6)
    void invitationLifecycleRejectsRevokedExpiredAndUnauthorizedLinks() throws SQLException {
        String suffix = uniqueSuffix();
        String calendarName = "Invitation lifecycle " + suffix;
        String adminUsername = "lifecycle-admin-" + suffix;
        String creatorUsername = "lifecycle-creator-" + suffix;
        String targetUsername = "lifecycle-target-" + suffix;
        long adminId = seedUser(adminUsername, "Lifecycle admin " + suffix);
        long creatorId = seedUser(creatorUsername, "Lifecycle creator " + suffix);
        seedUser(targetUsername, "Lifecycle target " + suffix);
        SeededCalendar calendar = seedCalendar(adminId, calendarName);
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) values (?, ?, 'EDITOR')",
                calendar.id(),
                creatorId);

        try (BrowserContext adminContext = newBrowserContext();
                BrowserContext creatorContext = newBrowserContext();
                BrowserContext targetContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            Page creatorPage = creatorContext.newPage();
            Page targetPage = targetContext.newPage();
            signIn(adminPage, adminUsername, TEST_PASSWORD);
            signIn(creatorPage, creatorUsername, TEST_PASSWORD);
            signIn(targetPage, targetUsername, TEST_PASSWORD);

            String unauthorizedCreatorLink = createEditorInvitation(creatorPage, calendarName);
            assertThat(creatorPage.locator(".app-nav a[aria-current='page']")).hasText("Invitations");
            assertThat(creatorPage.locator(".invitation-card .secondary-line").first())
                    .containsText("UTC · Expires");
            assertResponsiveAndAccessible(creatorPage, 1280, 900);
            creatorPage.locator("button:has-text('Copy generated link')").click();
            creatorPage.waitForFunction(
                    "() => document.querySelector('.generated-invitation .copy-status')"
                            + ".textContent.trim().length > 0");

            navigate(adminPage, "/app/calendar-members?id=" + calendar.id());
            Locator creatorCard = memberCard(adminPage, "Lifecycle creator " + suffix);
            creatorCard.locator("button:has-text('Remove access')").click();
            adminPage.locator(".ui-confirmdialog-yes").click();
            assertThat(adminPage.locator("body")).containsText("Member access removed.");

            Response unauthorizedResponse = targetPage.navigate(unauthorizedCreatorLink);
            assertSecurityHeaders(unauthorizedResponse);
            assertThat(targetPage.locator("body")).containsText("Invitation unavailable");

            String revokedLink = createEditorInvitation(adminPage, calendarName);
            adminPage.locator("article.invitation-card").first()
                    .locator("button:has-text('Revoke')")
                    .click();
            adminPage.locator(".ui-confirmdialog-yes").click();
            assertThat(adminPage.locator("body")).containsText("Invitation revoked.");
            Response revokedResponse = targetPage.navigate(revokedLink);
            assertSecurityHeaders(revokedResponse);
            assertThat(targetPage.locator("body")).containsText("Invitation unavailable");

            String expiredToken = "A".repeat(43);
            executeUpdate(
                    "insert into invitation("
                            + "calendar_id, invitation_token, created_by_user_id, created_at, expires_at"
                            + ") values (?, ?, ?, "
                            + "current_timestamp - interval '7 days 1 minute', "
                            + "current_timestamp - interval '1 minute')",
                    calendar.id(),
                    expiredToken,
                    adminId);
            String expiredLink = route("/register?token=" + expiredToken);
            Response expiredResponse = targetPage.navigate(expiredLink);
            assertSecurityHeaders(expiredResponse);
            assertThat(targetPage.locator("body")).containsText("Invitation unavailable");
        }
    }

    private String attemptBootstrapRegistration(
            String username,
            String displayName,
            String calendarName,
            CountDownLatch formsReady,
            CountDownLatch submitForms) {
        try (Playwright isolatedPlaywright = Playwright.create();
                Browser isolatedBrowser = isolatedPlaywright
                        .chromium()
                        .launch(new BrowserType.LaunchOptions().setHeadless(true));
                BrowserContext context = isolatedBrowser.newContext(
                        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
            Page page = context.newPage();
            navigateBearerLink(
                    page,
                    route("/register?token=" + BOOTSTRAP_INVITATION_TOKEN));
            fillRegistration(page, username, displayName, calendarName, TEST_PASSWORD);
            formsReady.countDown();
            if (!submitForms.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent registration was not released.");
            }
            page.locator("button:has-text('Register')")
                    .click(new Locator.ClickOptions().setTimeout(Duration.ofSeconds(60).toMillis()));
            page.waitForLoadState();
            return URI.create(page.url()).getPath();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "A concurrent disposable registration request failed.",
                    exception);
        }
    }

    private InvitationAcceptanceResult attemptEditorInvitationAcceptance(
            String username,
            String invitationLink,
            CountDownLatch formsReady,
            CountDownLatch submitForms) {
        try (Playwright isolatedPlaywright = Playwright.create();
                Browser isolatedBrowser = isolatedPlaywright
                        .chromium()
                        .launch(new BrowserType.LaunchOptions().setHeadless(true));
                BrowserContext context = isolatedBrowser.newContext(
                        new Browser.NewContextOptions().setIgnoreHTTPSErrors(true))) {
            Page page = context.newPage();
            signIn(page, username, TEST_PASSWORD);
            navigateBearerLink(page, invitationLink);
            Locator acceptInvitationButton = page.locator("button:has-text('Accept invitation')");
            if (!acceptInvitationButton.isVisible()) {
                throw new IllegalStateException("The editor invitation form was not available.");
            }
            formsReady.countDown();
            if (!submitForms.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent invitation acceptance was not released.");
            }
            acceptInvitationButton.click(
                    new Locator.ClickOptions().setTimeout(Duration.ofSeconds(60).toMillis()));
            page.waitForLoadState();
            return new InvitationAcceptanceResult(
                    username,
                    URI.create(page.url()).getPath(),
                    page.locator("body").innerText());
        } catch (Exception exception) {
            formsReady.countDown();
            throw new IllegalStateException(
                    "A concurrent editor invitation request failed.",
                    exception);
        }
    }

    private Locator memberCard(Page page, String displayName) {
        return page.locator("article.member-card").filter(
                new Locator.FilterOptions().setHasText(displayName));
    }

    private String membershipRole(long calendarId, String username) throws SQLException {
        return queryText(
                "select role_name from calendar_membership membership "
                        + "join app_user application_user "
                        + "on application_user.id = membership.user_id "
                        + "where membership.calendar_id = ? "
                        + "and application_user.username = ?",
                calendarId,
                username);
    }

    private record InvitationAcceptanceResult(
            String username,
            String resultingPath,
            String pageText) {}
}
