package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ReducedMotion;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class RecoveryAndAccessibilityEndToEndIT extends SharedCalendarEndToEndSupport {
    @Test
    void representativeAnonymousAndAuthenticatedPagesHaveNoAutomaticAccessibilityViolations()
            throws SQLException {
        String uniqueSuffix = uniqueSuffix();
        String username = "axe-owner-" + uniqueSuffix;
        String calendarName = "Accessible calendar " + uniqueSuffix;
        String eventTitle = "Accessible event " + uniqueSuffix;
        seedUser(username);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            navigateToBearerLink(page, route("/"));
            assertNoAutomaticAccessibilityViolations(page, "anonymous home page");
            navigateToBearerLink(page, route("/login"));
            assertNoAutomaticAccessibilityViolations(page, "sign-in page");
            navigateToBearerLink(page, route("/register"));
            assertNoAutomaticAccessibilityViolations(page, "registration page");

            signIn(page, username, TEST_PASSWORD);
            assertNoAutomaticAccessibilityViolations(page, "calendar list");
            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            createEvent(
                    page,
                    eventTitle,
                    "Accessible location",
                    "2027-04-10 10:00",
                    "2027-04-10 11:00",
                    false);
            String calendarId = Long.toString(findCalendarId(calendarName));
            String publicCalendarLink = page.url();
            assertNoAutomaticAccessibilityViolations(page, "editable calendar");

            navigateToBearerLink(page, route("/app/calendar-settings?id=" + calendarId));
            assertNoAutomaticAccessibilityViolations(page, "calendar settings");
            navigateToBearerLink(page, route("/app/calendar-members?id=" + calendarId));
            assertNoAutomaticAccessibilityViolations(page, "calendar members");
            navigateToBearerLink(page, route("/app/invitations"));
            assertNoAutomaticAccessibilityViolations(page, "invitation management");

            signOut(page);
            navigateToBearerLink(page, publicCalendarLink);
            assertBodyContains(page, eventTitle);
            assertNoAutomaticAccessibilityViolations(page, "public read-only calendar");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void applicationRestartInvalidatesMemorySessionsWhilePreservingCalendarData() throws Exception {
        Assumptions.assumeTrue(
                isDockerComposeManagedEndToEndEnvironment(),
                "Application restart scenario requires the repository's isolated end-to-end Docker Compose services.");
        String uniqueSuffix = uniqueSuffix();
        String username = "restart-owner-" + uniqueSuffix;
        String calendarName = "Restart calendar " + uniqueSuffix;
        String eventTitle = "Persisted restart event " + uniqueSuffix;
        seedUser(username);
        List<String> browserMessages = new ArrayList<>();

        try (BrowserContext browserContext = browser.newContext();
                BrowserContext staleLoginContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            Page staleLoginPage = newPage(staleLoginContext, browserMessages);
            signIn(page, username, TEST_PASSWORD);
            createCalendar(page, calendarName);
            openCalendar(page, calendarName);
            createEvent(
                    page,
                    eventTitle,
                    "Restart persistence location",
                    "2027-05-15 10:00",
                    "2027-05-15 11:00",
                    false);
            String publicCalendarLink = page.url();
            navigateToBearerLink(staleLoginPage, route("/login"));
            staleLoginPage.locator("input[id$='username']").fill(username);
            staleLoginPage.locator("input[id$='password']").fill(TEST_PASSWORD);

            boolean applicationWasStopped = false;
            try {
                runDockerComposeCommand(
                        "stop isolated end-to-end application",
                        "stop",
                        "web-e2e-verification");
                applicationWasStopped = true;
                waitForApplicationToBecomeUnavailable();
            } finally {
                if (applicationWasStopped) {
                    runDockerComposeCommand(
                            "start isolated end-to-end application",
                            "start",
                            "web-e2e-verification");
                    waitForHealthResponse(200, "ok", Duration.ofSeconds(120));
                }
            }

            navigateToBearerLink(page, publicCalendarLink);
            assertBodyContains(page, calendarName);
            assertBodyContains(page, eventTitle);
            assertBodyContains(page, "Read-only");
            assertEquals(
                    0,
                    page.locator("button:has-text('Create event')").count(),
                    "The in-memory authenticated session must not survive an application restart.");

            clickAndWaitForNavigation(
                    staleLoginPage,
                    staleLoginPage.locator("button:has-text('Sign in')"),
                    "expired sign-in form submission after restart");
            staleLoginPage.waitForURL("**/login?reauthenticationRequired=true");
            assertBodyContains(staleLoginPage, "Your session is no longer valid. Sign in again.");
            submitSignInAndWaitForUrl(
                    staleLoginPage,
                    username,
                    TEST_PASSWORD,
                    "**/app/calendars");

            signIn(page, username, TEST_PASSWORD);
            openCalendar(page, calendarName);
            assertBodyContains(page, eventTitle);
            assertBodyContains(page, "Restart persistence location");
            assertNoBrowserMessages(browserMessages);
        }
    }

    @Test
    void databaseOutageMakesHealthUnavailableAndTheApplicationRecoversAfterConnectivityReturns()
            throws Exception {
        Assumptions.assumeTrue(
                isDockerComposeManagedEndToEndEnvironment(),
                "Database recovery scenario requires the repository's isolated end-to-end Docker Compose services.");
        String uniqueSuffix = uniqueSuffix();
        String username = "database-recovery-owner-" + uniqueSuffix;
        String calendarName = "Recovered database calendar " + uniqueSuffix;
        seedUser(username);

        boolean databaseWasPaused = false;
        try {
            runDockerComposeCommand(
                    "pause isolated end-to-end database",
                    "pause",
                    "postgres-e2e-verification");
            databaseWasPaused = true;
            waitForHealthResponse(503, "unavailable", Duration.ofSeconds(45));
        } finally {
            if (databaseWasPaused) {
                runDockerComposeCommand(
                        "unpause isolated end-to-end database",
                        "unpause",
                        "postgres-e2e-verification");
                waitForHealthResponse(200, "ok", Duration.ofSeconds(120));
            }
        }

        List<String> browserMessages = new ArrayList<>();
        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            signIn(page, username, TEST_PASSWORD);
            createCalendar(page, calendarName);
            assertBodyContains(page, calendarName);
            assertEquals(1L, queryLong("select count(*) from calendar where name = ?", calendarName));
            assertNoBrowserMessages(browserMessages);
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

        List<String> setupBrowserMessages = new ArrayList<>();
        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = newPage(setupContext, setupBrowserMessages);
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            navigateToBearerLink(setupPage, route("/"));
            assertThat(setupPage.locator("#main-content a:has-text('My calendars')")).isVisible();
            assertEquals(0, setupPage.locator("a:has-text('Sign in')").count());
            navigateToBearerLink(setupPage, route("/app/calendars"));
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
            setupPage.locator("button:has-text('Copy generated link')").click();
            setupPage.waitForFunction(
                    "() => document.querySelector('.generated-invitation .copy-status').textContent.trim().length > 0");
            Locator generatedInvitationLink = setupPage.locator("input[id$='generatedInvitationLink']");
            String copyStatus = setupPage.locator(".generated-invitation .copy-status").textContent().trim();
            assertTrue(
                    copyStatus.equals("Link copied.")
                            || copyStatus.equals(
                                    "Clipboard access is unavailable. Select the link and copy it manually."));
            if (!copyStatus.equals("Link copied.")) {
                assertEquals(
                        true,
                        generatedInvitationLink.evaluate(
                                "element => element.selectionStart === 0 "
                                        + "&& element.selectionEnd === element.value.length"),
                        "The manual fallback should leave the entire invitation link selected.");
            }
            navigateToBearerLink(setupPage, calendarLink(calendarId));
            Locator longEventRow = setupPage.locator("article", new Page.LocatorOptions().setHasText(longEventTitle));
            Locator deleteEventButton = longEventRow.locator("button:has-text('Delete')");
            deleteEventButton.click();
            Locator confirmationDialog = visibleConfirmationDialog(setupPage);
            Locator cancelConfirmationButton = confirmationButton(setupPage, "Cancel");
            assertThat(cancelConfirmationButton).isFocused();
            assertVisibleOutline(cancelConfirmationButton);
            for (int tabPress = 0; tabPress < 4; tabPress++) {
                setupPage.keyboard().press("Tab");
                assertFocusRemainsWithin(confirmationDialog);
            }
            setupPage.keyboard().press("Escape");
            assertThat(setupPage.locator(".ui-confirm-dialog")).isHidden();
            assertThat(deleteEventButton).isFocused();
            assertBodyContains(setupPage, longEventTitle);
            calendarSettingsLink(setupPage).click();
            assertThat(setupPage.locator("h2:has-text('Calendar details')")).isVisible();
            assertThat(setupPage.locator("h2:has-text('Public access')")).isVisible();
            assertEquals("timeZoneHelp", setupPage.locator("input[id$='timeZone']").getAttribute("aria-describedby"));
            Locator publicAccessCheckbox = setupPage.getByLabel("Enable public read-only access");
            publicAccessCheckbox.focus();
            assertVisibleOutline(setupPage.locator(".ui-chkbox-box"));
            assertNoBrowserMessages(setupBrowserMessages);
        }

        try (BrowserContext semanticContext = browser.newContext()) {
            List<String> browserMessages = new ArrayList<>();
            Page page = newPage(semanticContext, browserMessages);
            navigateToBearerLink(page, route("/"));
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

            navigateToBearerLink(page, route("/login"));
            Locator usernameInput = page.locator("input[id$='username']");
            Locator passwordInput = page.locator("input[id$='password']");
            Locator signInButton = page.locator("button:has-text('Sign in')");
            pressTabUntilFocused(page, usernameInput, 10);
            page.keyboard().press("Tab");
            assertEquals(true, passwordInput.evaluate("element => document.activeElement === element"));
            page.keyboard().press("Tab");
            assertEquals(true, signInButton.evaluate("element => document.activeElement === element"));
            assertVisibleOutline(signInButton);
            assertTextContrast(signInButton, 4.5);
            assertControlBoundaryContrast(usernameInput, 3.0);
            assertRegionsHaveAccessibleNames(page);
            assertEquals(1, page.locator("h1").count());
            assertEquals("Sign in", page.locator("h1").textContent().trim());
            navigateToBearerLink(page, route("/register"));
            assertEquals(1, page.locator("h1").count());
            assertEquals("Create your calendar", page.locator("h1").textContent().trim());
            assertEquals(
                    "passwordRequirements",
                    page.locator("input[id$='password']").getAttribute("aria-describedby"));
            assertRegionsHaveAccessibleNames(page);
            navigateToBearerLink(page, route("/sign-in-error"));
            assertEquals(1, page.locator("h1").count());
            assertEquals("Sign-in error", page.locator("h1").textContent().trim());
            assertRegionsHaveAccessibleNames(page);
            assertNoBrowserMessages(browserMessages);
        }

        for (int viewportWidth : RESPONSIVE_WIDTHS) {
            List<String> browserMessages = new ArrayList<>();
            Browser.NewContextOptions options = new Browser.NewContextOptions().setViewportSize(viewportWidth, 900);
            try (BrowserContext browserContext = browser.newContext(options)) {
                Page page = newPage(browserContext, browserMessages);
                navigateToBearerLink(page, publicCalendarLink);
                assertEquals("en", page.locator("html").getAttribute("lang"));
                assertBodyContains(page, longCalendarName);
                assertBodyContains(page, longEventTitle);
                assertBodyContains(page, longEventLocation);
                assertBodyContains(page, longEventDescription);
                assertFalse(hasHorizontalOverflow(page), "Public page overflowed at " + viewportWidth + " pixels.");

                signIn(page, ownerUsername, TEST_PASSWORD);
                assertBodyContains(page, longCalendarName);
                assertFalse(hasHorizontalOverflow(page), "Authenticated page overflowed at " + viewportWidth + " pixels.");
                assertResponsiveTableRegion(page, "Calendars table", viewportWidth);
                assertControlCanBeBroughtIntoView(page, page.locator("a:has-text('Open')").first(), viewportWidth, 900);

                navigateToBearerLink(page, route("/app/account-settings"));
                assertEquals("Account settings", page.locator("h1").textContent().trim());
                assertFalse(hasHorizontalOverflow(page), "Account settings overflowed at " + viewportWidth + " pixels.");

                navigateToBearerLink(page, calendarLink(calendarId));
                assertBodyContains(page, longEventTitle);
                assertBodyContains(page, longEventLocation);
                assertBodyContains(page, longEventDescription);
                assertFalse(hasHorizontalOverflow(page), "Calendar page overflowed at " + viewportWidth + " pixels.");
                assertControlCanBeBroughtIntoView(
                        page, page.locator("button:has-text('Create event')"), viewportWidth, 900);

                navigateToBearerLink(page, route("/app/invitations"));
                assertResponsiveTableRegion(page, "Invitations table", viewportWidth);
                assertFalse(hasHorizontalOverflow(page), "Invitations page overflowed at " + viewportWidth + " pixels.");

                navigateToBearerLink(page, route("/app/calendar-members?id=" + calendarId));
                assertBodyContains(page, longDisplayName);
                assertResponsiveTableRegion(page, "Calendar members table", viewportWidth);
                assertFalse(hasHorizontalOverflow(page), "Members page overflowed at " + viewportWidth + " pixels.");
                assertNoBrowserMessages(browserMessages);
            }
        }

        for (int viewportWidth : new int[] {1024, 1280}) {
            List<String> browserMessages = new ArrayList<>();
            Browser.NewContextOptions options = new Browser.NewContextOptions().setViewportSize(viewportWidth, 600);
            try (BrowserContext browserContext = browser.newContext(options)) {
                Page page = newPage(browserContext, browserMessages);
                signIn(page, ownerUsername, TEST_PASSWORD);
                navigateToBearerLink(page, calendarLink(calendarId));
                assertControlCanBeBroughtIntoView(
                        page, page.locator("input[id$='eventTitle']"), viewportWidth, 600);
                assertControlCanBeBroughtIntoView(
                        page, page.locator("button:has-text('Create event')"), viewportWidth, 600);
                assertFalse(hasHorizontalOverflow(page), "Calendar page overflowed in a short viewport.");
                assertNoBrowserMessages(browserMessages);
            }
        }

        List<String> reducedMotionBrowserMessages = new ArrayList<>();
        try (BrowserContext reducedMotionContext = browser.newContext(
                new Browser.NewContextOptions().setReducedMotion(ReducedMotion.REDUCE))) {
            Page reducedMotionPage = newPage(reducedMotionContext, reducedMotionBrowserMessages);
            signIn(reducedMotionPage, ownerUsername, TEST_PASSWORD);
            navigateToBearerLink(reducedMotionPage, calendarLink(calendarId));
            assertEquals(true, reducedMotionPage.evaluate("matchMedia('(prefers-reduced-motion: reduce)').matches"));
            Locator eventRow = reducedMotionPage.locator(
                    "article", new Page.LocatorOptions().setHasText(longEventTitle));
            eventRow.locator("button:has-text('Delete')").click();
            Locator reducedMotionConfirmationButton = confirmationButton(reducedMotionPage, "Delete event");
            assertThat(reducedMotionConfirmationButton).isVisible();
            assertEquals(
                    true,
                    reducedMotionConfirmationButton.evaluate(
                            "element => getComputedStyle(element).animationName === 'none' "
                                    + "&& getComputedStyle(element).transitionDuration === '0s'"));
            reducedMotionPage.keyboard().press("Escape");
            assertNoBrowserMessages(reducedMotionBrowserMessages);
        }
    }

}
