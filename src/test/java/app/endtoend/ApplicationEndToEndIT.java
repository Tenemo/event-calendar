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
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.SameSiteAttribute;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
                "api_token,app_user,calendar,calendar_event,calendar_membership,"
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
            assertThat(page).hasTitle("calendar.social");
            assertThat(page.locator(".app-brand span")).hasText("calendar.social");
            String faviconUrl = page.locator("link[rel='icon']").getAttribute("href");
            String faviconQuery = URI.create(faviconUrl).getQuery();
            assertTrue(faviconQuery != null && faviconQuery.contains("ln=images"));
            assertTrue(faviconQuery.contains("revision="));
            assertEquals(
                    200,
                    ((Number) page.evaluate(
                                    "faviconUrl => fetch(faviconUrl).then(response => response.status)",
                                    faviconUrl))
                            .intValue(),
                    "The cache-revised favicon should load successfully.");
            assertFalse(
                    page.locator(".app-header")
                            .innerText()
                            .contains("Events for friends, clubs, and group plans."),
                    "The shared header should not display a subtitle.");
            Locator brandMark = page.locator(".app-brand-mark");
            assertThat(brandMark).isVisible();
            assertTrue(
                    Boolean.TRUE.equals(brandMark.evaluate(
                            "image => image.complete && image.naturalWidth > 0 "
                                    + "&& image.naturalHeight > 0")),
                    "The calendar mark should load successfully.");
            assertThat(page.locator("h1")).hasText("Shared event calendars for real plans");
            Locator homePreviewDates = page.locator(".home-preview-date");
            assertEquals(3, homePreviewDates.count(), "The homepage should show three example dates.");
            for (Locator homePreviewDate : homePreviewDates.all()) {
                assertTrue(
                        Boolean.TRUE.equals(homePreviewDate.evaluate(
                                "tile => {"
                                        + "const children = tile.children;"
                                        + "if (getComputedStyle(tile).display !== 'flex' "
                                        + "|| children.length !== 2) return false;"
                                        + "const tileBounds = tile.getBoundingClientRect();"
                                        + "const firstLineBounds = children[0].getBoundingClientRect();"
                                        + "const lastLineBounds = children[1].getBoundingClientRect();"
                                        + "const topSpace = firstLineBounds.top - tileBounds.top;"
                                        + "const bottomSpace = tileBounds.bottom - lastLineBounds.bottom;"
                                        + "return Math.abs(topSpace - bottomSpace) <= 1;"
                                        + "}")),
                        "Each example date should be vertically centered inside its tile.");
            }
            assertSecurityHeaders(homeResponse);
            assertEquals(
                    "dark",
                    page.evaluate("() => getComputedStyle(document.documentElement).colorScheme"));
            assertEquals(
                    page.evaluate(
                            "() => getComputedStyle(document.documentElement).backgroundColor"),
                    page.evaluate("() => getComputedStyle(document.body).backgroundColor"),
                    "The body background should resolve to the canvas token.");
            assertFalse(Boolean.TRUE.equals(page.evaluate(
                    "() => document.documentElement.scrollWidth "
                            + "> document.documentElement.clientWidth")));
            assertMobileNavigationTargets(page);
            assertAccessible(page);

            page.keyboard().press("Tab");
            assertThat(page.locator(".skip-link:focus")).hasText("Skip to main content");
            page.keyboard().press("Enter");
            assertEquals("main-content", page.evaluate("document.activeElement.id"));

            page.setViewportSize(1920, 900);
            assertHeaderUsesViewportWidth(page);

            navigate(page, "/sign-in");
            assertThat(page).hasTitle("Sign in - calendar.social");
            Locator usernameField = page.locator("input[id$='username']");
            Locator passwordField = page.locator("input[id$='password']");
            Locator signInButton = page.locator("input[type='submit'][value='Sign in']");
            assertThat(usernameField).isFocused();
            page.keyboard().press("Tab");
            assertThat(passwordField).isFocused();
            page.keyboard().press("Tab");
            assertThat(signInButton).isFocused();
            assertAccessible(page);

            assertEquals(200, navigate(page, "/error.html").status());
            Locator errorPageStylesheets = page.locator("link[rel='stylesheet']");
            assertEquals(2, errorPageStylesheets.count());
            assertEquals(
                    "/resources/css/tokens.css",
                    errorPageStylesheets.nth(0).getAttribute("href"));
            assertEquals(
                    "/resources/css/error-page.css",
                    errorPageStylesheets.nth(1).getAttribute("href"));

            navigate(page, "/app/calendars");
            page.waitForURL("**/sign-in");
        }

    }

    @Test
    @Order(2)
    void expiredSignInFormReturnsAFreshFormInsteadOfAServerError() {
        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            navigate(page, "/sign-in");
            page.locator("input[id$='username']").fill("stale-form-user");
            page.locator("input[id$='password']").fill(TEST_PASSWORD);

            context.clearCookies();
            Response expiredFormResponse = page.waitForResponse(
                    response -> "POST".equals(response.request().method())
                            && "/sign-in".equals(URI.create(response.url()).getPath()),
                    () -> page.locator("input[type='submit'][value='Sign in']").click());

            assertEquals(302, expiredFormResponse.status());
            page.waitForURL("**/sign-in?viewExpired=true");
            assertThat(page.locator(".view-expired-message"))
                    .containsText("This page expired while it was open. Please try again.");
            assertThat(page.locator("input[id$='username']")).isFocused();
            assertAccessible(page);
        }
    }

    @Test
    @Order(3)
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
    @Order(4)
    void authenticationInvitedRegistrationAndPasswordChangeWork() throws SQLException {
        String suffix = uniqueSuffix();
        String inviterUsername = "inviter-" + suffix;
        long inviterId = seedUser(inviterUsername, "Inviter " + suffix);
        seedCalendar(inviterId, "Invitation calendar " + suffix);

        try (BrowserContext inviterContext = newBrowserContext()) {
            Page inviterPage = inviterContext.newPage();
            navigate(inviterPage, "/sign-in");
            assertThat(inviterPage.locator("input[id$='username']")).isFocused();
            inviterPage.locator("input[id$='username']").fill(inviterUsername);
            inviterPage.locator("input[id$='password']").fill("definitely wrong");
            inviterPage.locator("input[type='submit'][value='Sign in']").click();
            assertThat(inviterPage.locator("body")).containsText("Sign-in failed.");
            assertThat(inviterPage.locator("input[id$='password']")).isFocused();
            Locator signInMessages = inviterPage.locator("[id$='messages']");
            assertThat(signInMessages).hasAttribute("role", "alert");
            assertThat(signInMessages).hasAttribute("aria-live", "assertive");
            assertThat(signInMessages.locator(".lightweight-message-error"))
                    .containsText("Sign-in failed.");
            assertFalse(
                    (Boolean) inviterPage.evaluate(
                            "() => Array.from(document.scripts).some(script => "
                                    + "script.src.includes('/primefaces/components.js'))"),
                    "The native sign-in form must not load the PrimeFaces component bundle.");
            assertAccessible(inviterPage);

            inviterPage.locator("input[id$='password']").fill(TEST_PASSWORD);
            inviterPage.locator("input[id$='password']").press("Enter");
            inviterPage.waitForURL("**/app/calendars");
            Cookie authenticationCookie = inviterContext.cookies(route("/")).stream()
                    .filter(cookie -> "LtpaToken2".equals(cookie.name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Authentication cookie was not issued."));
            assertTrue(Boolean.TRUE.equals(authenticationCookie.secure));
            assertTrue(Boolean.TRUE.equals(authenticationCookie.httpOnly));
            assertEquals(SameSiteAttribute.LAX, authenticationCookie.sameSite);

            assertUnifiedButtonGeometry(inviterPage);

            Locator calendarCard = inviterPage.locator("article.calendar-card").filter(
                    new Locator.FilterOptions().setHasText("Invitation calendar " + suffix));
            Locator calendarCardLink = calendarCard.locator(".calendar-card-link");
            String calendarPath = URI.create(calendarCardLink.getAttribute("href")).getPath();
            BoundingBox restingCalendarCardBounds = calendarCard.boundingBox();
            String restingCalendarCardBackground = computedStyle(calendarCard, "backgroundColor");
            String restingCalendarCardBorder = computedStyle(calendarCard, "borderColor");
            calendarCard.hover();
            waitForElementAnimations(calendarCard);
            BoundingBox highlightedCalendarCardBounds = calendarCard.boundingBox();
            assertFalse(
                    restingCalendarCardBackground.equals(
                            computedStyle(calendarCard, "backgroundColor")),
                    "Hovering a clickable calendar card should highlight its background.");
            assertFalse(
                    restingCalendarCardBorder.equals(computedStyle(calendarCard, "borderColor")),
                    "Hovering a clickable calendar card should highlight its border.");
            assertEquals(
                    restingCalendarCardBounds.y,
                    highlightedCalendarCardBounds.y,
                    0.01,
                    "Hovering a calendar card should not move it.");
            assertEquals(
                    "none",
                    computedStyle(calendarCard, "transform"),
                    "Hovering a calendar card should not move it.");
            assertEquals(
                    "pointer",
                    computedStyle(calendarCard, "cursor"),
                    "A clickable calendar card should use the pointer cursor.");
            calendarCardLink.focus();
            assertThat(calendarCardLink).isFocused();
            assertEquals(
                    highlightedCalendarCardBounds.y,
                    calendarCard.boundingBox().y,
                    0.01,
                    "Keyboard focus should not move a calendar card.");

            inviterPage.mouse().click(
                    highlightedCalendarCardBounds.x + highlightedCalendarCardBounds.width / 2,
                    highlightedCalendarCardBounds.y + highlightedCalendarCardBounds.height / 2);
            inviterPage.waitForURL(url -> URI.create(url).getPath().equals(calendarPath));
            assertThat(inviterPage.locator("h1"))
                    .hasText("Invitation calendar " + suffix);

            navigate(inviterPage, "/app/calendars");
            Locator calendarSettingsLink = inviterPage
                    .locator("article.calendar-card")
                    .filter(new Locator.FilterOptions()
                            .setHasText("Invitation calendar " + suffix))
                    .locator("a[aria-label='Open settings for Invitation calendar "
                            + suffix
                            + "']");
            calendarSettingsLink.click();
            inviterPage.waitForURL("**/app/calendar-settings?id=*");
            assertThat(inviterPage.locator("h1")).hasText("Settings");
            navigate(inviterPage, "/app/calendars");

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
                assertThat(registeredPage.locator("input[id$='username']")).isFocused();
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
                assertThat(registeredPage.locator("input[id$='currentPassword']")).isFocused();
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
    @Order(5)
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
            assertThat(ownerPage.locator(".calendar-schedule.fc")).isVisible();
            Locator previousMonthButton = ownerPage.locator(".fc-prev-button");
            Locator nextMonthButton = ownerPage.locator(".fc-next-button");
            assertThat(previousMonthButton).hasText("Previous");
            assertThat(nextMonthButton).hasText("Next");
            assertCalendarNavigationIcons(previousMonthButton, nextMonthButton);
            assertThat(ownerPage.locator(".fc-dayGridMonth-button")).hasText("Month");
            assertThat(ownerPage.locator(".fc-timeGridWeek-button")).hasText("Week");
            assertEquals(
                    0,
                    ownerPage.locator(".fc-listMonth-button").count(),
                    "Agenda should be a permanent sidebar, not a calendar view button.");
            Locator agenda = ownerPage.locator(".calendar-agenda");
            Locator calendarSidebar = ownerPage.locator(".calendar-sidebar");
            assertThat(agenda).isVisible();
            assertThat(agenda.locator("h2")).hasText("Agenda");
            assertThat(agenda).containsText("No events yet.");
            assertThat(calendarSidebar).isVisible();
            assertThat(calendarSidebar.locator("h1")).hasText(createdCalendarName);
            assertThat(calendarSidebar).containsText("Times use Europe/Warsaw.");
            BoundingBox desktopScheduleBounds = ownerPage.locator(".calendar-schedule").boundingBox();
            BoundingBox desktopAgendaBounds = agenda.boundingBox();
            BoundingBox desktopSidebarBounds = calendarSidebar.boundingBox();
            assertTrue(
                    desktopSidebarBounds != null
                            && desktopScheduleBounds != null
                            && desktopAgendaBounds != null,
                    "The desktop sidebars and calendar should have visible bounds.");
            assertTrue(
                    desktopSidebarBounds.x + desktopSidebarBounds.width < desktopScheduleBounds.x,
                    "The calendar information should remain beside and left of the calendar on desktop.");
            assertTrue(
                    desktopAgendaBounds.x > desktopScheduleBounds.x + desktopScheduleBounds.width,
                    "The agenda should remain beside the calendar on desktop.");
            assertResponsiveAndAccessible(ownerPage, 320, 900);
            assertMobileNavigationTargets(ownerPage);
            BoundingBox phoneScheduleBounds = ownerPage.locator(".calendar-schedule").boundingBox();
            BoundingBox phoneAgendaBounds = agenda.boundingBox();
            BoundingBox phoneSidebarBounds = calendarSidebar.boundingBox();
            assertTrue(
                    phoneSidebarBounds != null
                            && phoneScheduleBounds != null
                            && phoneAgendaBounds != null,
                    "The phone calendar information, calendar, and agenda should have visible bounds.");
            assertTrue(
                    phoneScheduleBounds.y >= phoneSidebarBounds.y + phoneSidebarBounds.height,
                    "The calendar information should precede the calendar on a phone.");
            assertTrue(
                    phoneAgendaBounds.y >= phoneScheduleBounds.y + phoneScheduleBounds.height,
                    "The permanent agenda should follow the calendar on a phone.");
            ownerPage.setViewportSize(1280, 900);
            ownerPage.locator(".fc-dayGridMonth-button").click();

            ownerPage.locator("button:has-text('New event')").click();
            assertThat(ownerPage.locator(".event-dialog .ui-dialog-title"))
                    .hasText("Create event");
            Locator eventTitleInput = ownerPage.locator("input[id$='eventTitle']");
            assertThat(eventTitleInput).isFocused();
            String controlBorderColor = computedStyle(eventTitleInput, "borderTopColor");
            String dialogBackgroundColor = computedStyle(
                    ownerPage.locator(".event-dialog .ui-dialog-content"),
                    "backgroundColor");
            double controlBoundaryContrast = contrastRatio(
                    controlBorderColor,
                    dialogBackgroundColor);
            assertTrue(
                    controlBoundaryContrast >= 3,
                    () -> "Dialog control boundary contrast should be at least 3:1, but was "
                            + controlBoundaryContrast
                            + ":1.");
            ownerPage.locator(".event-dialog button:has-text('Create event')").click();
            assertThat(ownerPage.locator("body")).containsText("Event title is required.");
            ownerPage.waitForFunction(
                    "() => document.activeElement && document.activeElement.id.endsWith('eventTitle')");

            Locator startDatePickerButton =
                    ownerPage.locator("span[id$='eventStart'] .ui-datepicker-trigger");
            startDatePickerButton.click();
            Locator visibleDatePicker = ownerPage.locator(".event-date-picker-panel:visible");
            assertThat(visibleDatePicker).isVisible();
            BoundingBox datePickerBounds = visibleDatePicker.boundingBox();
            assertTrue(datePickerBounds != null, "The date picker should have visible bounds.");
            assertTrue(
                    datePickerBounds.width <= 320,
                    () -> "The date picker should remain compact, but was "
                            + datePickerBounds.width
                            + "px wide.");
            assertTrue(
                    datePickerBounds.height <= 420,
                    () -> "The date picker should remain compact, but was "
                            + datePickerBounds.height
                            + "px tall.");
            assertEquals(
                    2,
                    visibleDatePicker.locator(".ui-timepicker-timeinput input").count(),
                    "Timed date pickers should expose typed hour and minute controls.");
            assertEquals(
                    0,
                    visibleDatePicker
                            .locator(".ui-picker-up:visible, .ui-picker-down:visible")
                            .count(),
                    "Typed time controls should not retain the old spinner arrows.");
            ownerPage.keyboard().press("Escape");
            ownerPage.keyboard().press("Escape");
            assertThat(ownerPage.locator(".event-dialog")).isHidden();
            ownerPage.locator("button:has-text('New event')").click();
            assertThat(ownerPage.locator("input[id$='eventTitle']")).isFocused();

            createTimedEvent(
                    ownerPage,
                    eventTitle,
                    "River bank",
                    "22 Aug 2026, 10:00",
                    "22 Aug 2026, 13:00");

            Locator agendaEvent = ownerPage.locator(".calendar-agenda-event").filter(
                    new Locator.FilterOptions().setHasText(eventTitle));
            assertThat(agendaEvent).isVisible();
            assertEquals(
                    "pointer",
                    computedStyle(agendaEvent, "cursor"),
                    "The full agenda card should be clickable.");
            agendaEvent.click();
            assertThat(ownerPage.locator(".event-dialog .ui-dialog-title"))
                    .hasText("Edit event");
            assertThat(ownerPage.locator("input[id$='eventTitle']")).isFocused();
            ownerPage.locator(".event-dialog .ui-dialog-titlebar-close").click();

            Locator calendarEvent = ownerPage.locator(".calendar-schedule .fc-event").filter(
                    new Locator.FilterOptions().setHasText(eventTitle));
            assertEquals(
                    "pointer",
                    computedStyle(calendarEvent, "cursor"),
                    "The full calendar event should be clickable.");
            calendarEvent.scrollIntoViewIfNeeded();
            BoundingBox restingEventBounds = calendarEvent.boundingBox();
            assertTrue(restingEventBounds != null, "The calendar event should have visible bounds.");
            calendarEvent.hover();
            waitForElementAnimations(calendarEvent);
            BoundingBox highlightedEventBounds = calendarEvent.boundingBox();
            assertTrue(
                    highlightedEventBounds != null,
                    "The highlighted calendar event should have visible bounds.");
            assertEquals(
                    restingEventBounds.y,
                    highlightedEventBounds.y,
                    0.1,
                    "Hovering a calendar event should not move it.");
            calendarEvent.click();
            assertThat(ownerPage.locator(".event-dialog .ui-dialog-title"))
                    .hasText("Edit event");
            assertThat(ownerPage.locator("input[id$='eventTitle']")).isFocused();
            assertThat(ownerPage.locator(".event-dialog button:has-text('Save changes')"))
                    .isVisible();
            String updatedEventTitle = eventTitle + " updated";
            ownerPage.locator("input[id$='eventTitle']").fill(updatedEventTitle);
            ownerPage.locator(".event-dialog button:has-text('Save changes')").click();
            assertThat(ownerPage.locator("body")).containsText("Event updated.");
            assertThat(ownerPage.locator(".calendar-schedule .fc-event").filter(
                             new Locator.FilterOptions().setHasText(updatedEventTitle)))
                    .isVisible();
            assertThat(ownerPage.locator(".calendar-agenda-event").filter(
                            new Locator.FilterOptions().setHasText(updatedEventTitle)))
                    .isVisible();

            ownerPage.locator("button:has-text('New event')").click();
            ownerPage.locator(".event-dialog .ui-chkbox-box").click();
            ownerPage.locator("input[id$='eventTitle']").fill(allDayEventTitle);
            ownerPage.locator("input[id$='eventFirstDay_input']").fill("23 Aug 2026");
            ownerPage.locator("input[id$='eventLastDay_input']").fill("24 Aug 2026");
            ownerPage.locator("textarea[id$='eventDescription']").click();
            ownerPage.locator(".event-dialog button:has-text('Create event')").click();
            assertThat(ownerPage.locator(".calendar-schedule .calendar-event-all-day").filter(
                             new Locator.FilterOptions().setHasText(allDayEventTitle))
                    .first())
                    .isVisible();
            assertThat(ownerPage.locator(".calendar-agenda-event-all-day").filter(
                            new Locator.FilterOptions().setHasText(allDayEventTitle)))
                    .isVisible();
            ownerPage.locator(".fc-timeGridWeek-button").click();
            assertThat(ownerPage.locator(".fc-timeGridWeek-view")).isVisible();
            ownerPage.locator(".fc-dayGridMonth-button").click();
            assertThat(ownerPage.locator(".fc-dayGridMonth-view")).isVisible();
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
            assertThat(readerPage.locator(".calendar-schedule.fc")).isVisible();
            assertThat(readerPage.locator(".calendar-agenda")).isVisible();
            assertEquals(0, readerPage.locator("button:has-text('New event')").count());
            readerPage.locator(".calendar-schedule .fc-event").filter(
                            new Locator.FilterOptions().setHasText(updatedEventTitle))
                    .click();
            assertThat(readerPage.locator(".event-dialog .ui-dialog-title"))
                    .hasText("Event details");
            assertThat(readerPage.locator(".event-details h2")).hasText(updatedEventTitle);
            assertThat(readerPage.locator(".event-details")).containsText("River bank");
            readerPage.locator(".event-dialog .ui-dialog-titlebar-close").click();

            navigate(ownerPage, "/app/calendar-settings?id=" + calendarId);
            assertThat(ownerPage.locator("input[id$='calendarName']")).isFocused();
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
            ownerPage.locator(".calendar-schedule .fc-event").filter(
                            new Locator.FilterOptions().setHasText(allDayEventTitle))
                    .first()
                    .click();
            assertThat(ownerPage.locator("input[id$='eventFirstDay_input']"))
                    .hasValue("23 Aug 2026");
            assertThat(ownerPage.locator("input[id$='eventLastDay_input']"))
                    .hasValue("24 Aug 2026");
            ownerPage.locator(".event-dialog .ui-dialog-titlebar-close").click();
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
    @Order(6)
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
                                    && result.pageText()
                                            .contains("Invitation is invalid or no longer available."))
                            .count(),
                    "The request that loses the invitation race should receive the canonical rejection. Results: "
                            + acceptanceResults);

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
                assertEquals(200, navigate(editorPage, acceptedCalendarPath).status());
                assertEquals(
                        0,
                        editorPage.locator("button:has-text('Regenerate link')").count(),
                        "Editors must not be offered calendar-link administration.");
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
    @Order(7)
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

    @Test
    @Order(8)
    void indefiniteApiTokensProvideLiveAccountPermissionsUntilRevoked() throws SQLException {
        String suffix = uniqueSuffix();
        String adminUsername = "api-admin-" + suffix;
        String editorUsername = "api-editor-" + suffix;
        String calendarName = "API calendar " + suffix;
        String adminTokenName = "Admin integration " + suffix;
        String editorTokenName = "Editor integration " + suffix;
        long adminId = seedUser(adminUsername, "API admin " + suffix);
        long editorId = seedUser(editorUsername, "API editor " + suffix);
        SeededCalendar calendar = seedCalendar(adminId, calendarName);
        String otherCalendarName = "Other API calendar " + suffix;
        SeededCalendar otherCalendar = seedCalendar(adminId, otherCalendarName);
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) values (?, ?, 'EDITOR')",
                calendar.id(),
                editorId);

        try (BrowserContext adminContext = newBrowserContext();
                BrowserContext editorContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            Page editorPage = editorContext.newPage();
            signIn(adminPage, adminUsername, TEST_PASSWORD);
            signIn(editorPage, editorUsername, TEST_PASSWORD);

            navigate(adminPage, "/app/account-settings");
            assertThat(adminPage.locator("body")).containsText("Tokens do not expire.");
            adminPage.locator("input[id$='tokenName']").fill(adminTokenName);
            adminPage.locator("button:has-text('Create API token')").click();
            String adminToken = adminPage.getByLabel(
                            "New API token", new Page.GetByLabelOptions().setExact(true))
                    .inputValue();
            assertTrue(
                    adminToken.matches("calendar_social_api_[A-Za-z0-9_-]{43}"),
                    "The issued API token should use the documented opaque format.");
            assertResponsiveAndAccessible(adminPage, 768, 900);

            String storedDigest = queryText(
                    "select token_digest from api_token where user_id = ? and name = ?",
                    adminId,
                    adminTokenName);
            assertEquals(64, storedDigest.length());
            assertFalse(storedDigest.contains(adminToken));
            assertEquals(
                    adminToken.substring(adminToken.length() - 8),
                    queryText(
                            "select token_hint from api_token where user_id = ? and name = ?",
                            adminId,
                            adminTokenName));
            assertEquals(
                    0,
                    queryLong(
                            "select count(*) from api_token where token_digest = ?",
                            adminToken));
            assertEquals(
                    0,
                    queryLong(
                            "select count(*) from information_schema.columns "
                                    + "where table_schema = 'public' and table_name = 'api_token' "
                                    + "and column_name in ('expires_at', 'scope', 'scopes')"));

            navigate(adminPage, "/app/account-settings");
            assertFalse(
                    adminPage.content().contains(adminToken),
                    "The plaintext token must disappear after its one-time response.");

            navigate(editorPage, "/app/account-settings");
            editorPage.locator("input[id$='tokenName']").fill(editorTokenName);
            editorPage.locator("button:has-text('Create API token')").click();
            String editorToken = editorPage.getByLabel(
                            "New API token", new Page.GetByLabelOptions().setExact(true))
                    .inputValue();

            Map<String, Object> openApiResponse = apiRequest(
                    adminPage, "/api/openapi", "GET", null, null, null);
            assertEquals(200, responseStatus(openApiResponse));
            assertTrue(String.valueOf(openApiResponse.get("body")).contains("calendar.social API"));
            Map<String, Object> openApiDocument = responseBody(openApiResponse);
            Map<String, Object> calendarPath = nestedBody(
                    nestedBody(openApiDocument, "paths"),
                    "/api/v1/calendars/{calendarId}");
            Map<String, Object> calendarUpdateOperation = nestedBody(calendarPath, "put");
            assertEquals(
                    1,
                    ((List<?>) calendarUpdateOperation.get("parameters")).size(),
                    "The design-first contract must not be merged with duplicate scanned parameters.");

            Map<String, Object> cookieOnlyResponse = apiRequest(
                    adminPage, "/api/v1/me", "GET", null, null, null);
            assertProblemResponse(cookieOnlyResponse, 401);
            assertEquals(
                    "Bearer realm=\"calendar.social\"",
                    cookieOnlyResponse.get("wwwAuthenticate"));

            Map<String, Object> publicLinkAsBearerResponse = apiRequest(
                    adminPage,
                    "/api/v1/me",
                    "GET",
                    calendar.calendarLinkToken(),
                    null,
                    null);
            assertProblemResponse(publicLinkAsBearerResponse, 401);

            Map<String, Object> callerResponse = apiRequest(
                    adminPage, "/api/v1/me", "GET", adminToken, null, null);
            assertEquals(200, responseStatus(callerResponse));
            assertEquals(adminUsername, responseBody(callerResponse).get("username"));

            Map<String, Object> calendarResponse = apiRequest(
                    adminPage,
                    "/api/v1/calendars/" + calendar.id(),
                    "GET",
                    adminToken,
                    null,
                    null);
            assertEquals(200, responseStatus(calendarResponse));
            assertEquals("ADMIN", responseBody(calendarResponse).get("role"));
            String calendarEntityTag = (String) calendarResponse.get("etag");

            String calendarSettings = """
                    {
                      "name": "%s",
                      "description": "Managed through the API",
                      "timeZone": "Europe/Warsaw",
                      "publicAccessEnabled": true
                    }
                    """.formatted(calendarName);
            Map<String, Object> staleCalendarUpdate = apiRequest(
                    adminPage,
                    "/api/v1/calendars/" + calendar.id(),
                    "PUT",
                    adminToken,
                    "\"999999\"",
                    calendarSettings);
            assertProblemResponse(staleCalendarUpdate, 412);

            Map<String, Object> updatedCalendarResponse = apiRequest(
                    adminPage,
                    "/api/v1/calendars/" + calendar.id(),
                    "PUT",
                    adminToken,
                    calendarEntityTag,
                    calendarSettings);
            assertEquals(200, responseStatus(updatedCalendarResponse));
            assertEquals("Managed through the API", responseBody(updatedCalendarResponse).get("description"));

            Map<String, Object> editorCalendarResponse = apiRequest(
                    editorPage,
                    "/api/v1/calendars/" + calendar.id(),
                    "GET",
                    editorToken,
                    null,
                    null);
            assertEquals(200, responseStatus(editorCalendarResponse));
            assertEquals("EDITOR", responseBody(editorCalendarResponse).get("role"));

            String eventTitle = "API kayaking " + suffix;
            String eventInput = """
                    {
                      "title": "%s",
                      "description": null,
                      "location": "Lake",
                      "time": {
                        "kind": "all-day",
                        "firstDay": "2026-08-10",
                        "lastDay": "2026-08-12"
                      }
                    }
                    """.formatted(eventTitle);
            Map<String, Object> createdEventResponse = apiRequest(
                    editorPage,
                    "/api/v1/calendars/" + calendar.id() + "/events",
                    "POST",
                    editorToken,
                    (String) editorCalendarResponse.get("etag"),
                    eventInput);
            assertEquals(201, responseStatus(createdEventResponse));
            Map<String, Object> createdEvent = responseBody(createdEventResponse);
            long eventId = ((Number) createdEvent.get("id")).longValue();
            Map<String, Object> createdEventTime = nestedBody(createdEvent, "time");
            assertEquals("2026-08-10", createdEventTime.get("firstDay"));
            assertEquals("2026-08-12", createdEventTime.get("lastDay"));

            Map<String, Object> wrongCalendarEventResponse = apiRequest(
                    adminPage,
                    "/api/v1/calendars/" + otherCalendar.id() + "/events/" + eventId,
                    "GET",
                    adminToken,
                    null,
                    null);
            assertProblemResponse(wrongCalendarEventResponse, 404);

            String updatedEventInput = """
                    {
                      "title": "%s updated",
                      "description": "Bring paddles",
                      "location": "Lake",
                      "time": {
                        "kind": "timed",
                        "start": "2026-08-10T18:30:00",
                        "end": "2026-08-10T20:00:00"
                      }
                    }
                    """.formatted(eventTitle);
            String initialEventEntityTag = (String) createdEventResponse.get("etag");
            Map<String, Object> updatedEventResponse = apiRequest(
                    editorPage,
                    "/api/v1/calendars/" + calendar.id() + "/events/" + eventId,
                    "PUT",
                    editorToken,
                    initialEventEntityTag,
                    updatedEventInput);
            assertEquals(200, responseStatus(updatedEventResponse));
            assertFalse(initialEventEntityTag.equals(updatedEventResponse.get("etag")));
            assertEquals("timed", nestedBody(responseBody(updatedEventResponse), "time").get("kind"));

            Map<String, Object> staleEventUpdateResponse = apiRequest(
                    editorPage,
                    "/api/v1/calendars/" + calendar.id() + "/events/" + eventId,
                    "PUT",
                    editorToken,
                    initialEventEntityTag,
                    updatedEventInput);
            assertProblemResponse(staleEventUpdateResponse, 412);

            Map<String, Object> editorMemberListResponse = apiRequest(
                    editorPage,
                    "/api/v1/calendars/" + calendar.id() + "/members",
                    "GET",
                    editorToken,
                    null,
                    null);
            assertProblemResponse(editorMemberListResponse, 403);

            String editorMembershipPath = "/api/v1/calendars/"
                    + calendar.id()
                    + "/members/"
                    + editorId;
            Map<String, Object> promotedEditorResponse = apiRequest(
                    adminPage,
                    editorMembershipPath,
                    "PUT",
                    adminToken,
                    null,
                    "{\"role\":\"ADMIN\"}");
            assertEquals(200, responseStatus(promotedEditorResponse));
            assertEquals("ADMIN", responseBody(promotedEditorResponse).get("role"));
            assertEquals(
                    200,
                    responseStatus(apiRequest(
                            editorPage,
                            "/api/v1/calendars/" + calendar.id() + "/members",
                            "GET",
                            editorToken,
                            null,
                            null)));

            assertEquals(
                    200,
                    responseStatus(apiRequest(
                            adminPage,
                            editorMembershipPath,
                            "PUT",
                            adminToken,
                            null,
                            "{\"role\":\"EDITOR\"}")));
            assertProblemResponse(
                    apiRequest(
                            editorPage,
                            "/api/v1/calendars/" + calendar.id() + "/members",
                            "GET",
                            editorToken,
                            null,
                            null),
                    403);

            Map<String, Object> invitationResponse = apiRequest(
                    adminPage,
                    "/api/v1/registration-invitations",
                    "POST",
                    adminToken,
                    null,
                    null);
            assertEquals(201, responseStatus(invitationResponse));
            long invitationId = ((Number) responseBody(invitationResponse).get("id")).longValue();
            assertEquals(
                    204,
                    responseStatus(apiRequest(
                            adminPage,
                            "/api/v1/invitations/" + invitationId,
                            "DELETE",
                            adminToken,
                            null,
                            null)));

            Map<String, Object> editorInvitationResponse = apiRequest(
                    adminPage,
                    "/api/v1/calendars/" + otherCalendar.id() + "/editor-invitations",
                    "POST",
                    adminToken,
                    null,
                    null);
            assertEquals(201, responseStatus(editorInvitationResponse));
            Map<String, Object> editorInvitation = responseBody(editorInvitationResponse);
            assertEquals("calendar-editor", editorInvitation.get("kind"));
            assertEquals(otherCalendar.id(), ((Number) editorInvitation.get("calendarId")).longValue());
            long editorInvitationId = ((Number) editorInvitation.get("id")).longValue();
            Map<String, Object> listedInvitationsResponse = apiRequest(
                    adminPage,
                    "/api/v1/invitations",
                    "GET",
                    adminToken,
                    null,
                    null);
            assertEquals(200, responseStatus(listedInvitationsResponse));
            Map<String, Object> listedEditorInvitation = responseList(listedInvitationsResponse)
                    .stream()
                    .filter(listedInvitation ->
                            ((Number) listedInvitation.get("id")).longValue() == editorInvitationId)
                    .findFirst()
                    .orElseThrow();
            assertEquals(otherCalendarName, listedEditorInvitation.get("calendarName"));

            String editorInvitationUrl = (String) editorInvitation.get("url");
            String editorInvitationToken = editorInvitationUrl.substring(
                    editorInvitationUrl.indexOf("?token=") + "?token=".length());
            String invitationAcceptanceBody = "{\"token\":\"" + editorInvitationToken + "\"}";
            Map<String, Object> acceptedInvitationResponse = apiRequest(
                    editorPage,
                    "/api/v1/invitation-acceptances",
                    "POST",
                    editorToken,
                    null,
                    invitationAcceptanceBody);
            assertEquals(200, responseStatus(acceptedInvitationResponse));
            assertEquals("EDITOR", responseBody(acceptedInvitationResponse).get("role"));
            assertEquals(
                    "EDITOR",
                    responseBody(apiRequest(
                                    editorPage,
                                    "/api/v1/calendars/" + otherCalendar.id(),
                                    "GET",
                                    editorToken,
                                    null,
                                    null))
                            .get("role"));
            assertProblemResponse(
                    apiRequest(
                            editorPage,
                            "/api/v1/invitation-acceptances",
                            "POST",
                            editorToken,
                            null,
                            invitationAcceptanceBody),
                    422);

            Map<String, Object> deletedEventResponse = apiRequest(
                    editorPage,
                    "/api/v1/calendars/" + calendar.id() + "/events/" + eventId,
                    "DELETE",
                    editorToken,
                    (String) updatedEventResponse.get("etag"),
                    null);
            assertEquals(204, responseStatus(deletedEventResponse));
            assertProblemResponse(
                    apiRequest(
                            editorPage,
                            "/api/v1/calendars/" + calendar.id() + "/events/" + eventId,
                            "GET",
                            editorToken,
                            null,
                            null),
                    404);

            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from api_token "
                                    + "where user_id = ? and name = ? and last_used_at is not null",
                            adminId,
                            adminTokenName));

            navigate(adminPage, "/app/account-settings");
            Locator adminTokenCard = adminPage.locator("article.list-card")
                    .filter(new Locator.FilterOptions().setHasText(adminTokenName));
            adminTokenCard.locator("button:has-text('Revoke')").click();
            adminPage.locator(".ui-confirmdialog-yes").click();
            assertThat(adminPage.locator("body")).containsText("API token revoked.");
            assertEquals(
                    0,
                    queryLong(
                            "select count(*) from api_token where user_id = ? and name = ?",
                            adminId,
                            adminTokenName));

            Map<String, Object> revokedTokenResponse = apiRequest(
                    adminPage, "/api/v1/me", "GET", adminToken, null, null);
            assertProblemResponse(revokedTokenResponse, 401);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiRequest(
            Page page,
            String path,
            String method,
            String token,
            String entityTag,
            String requestBody) {
        Map<String, Object> request = new HashMap<>();
        request.put("path", path);
        request.put("method", method);
        request.put("token", token);
        request.put("entityTag", entityTag);
        request.put("requestBody", requestBody);
        return (Map<String, Object>) page.evaluate(
                """
                async request => {
                    const headers = {Accept: "application/json"};
                    if (request.token !== null) {
                        headers.Authorization = `Bearer ${request.token}`;
                    }
                    if (request.entityTag !== null) {
                        headers["If-Match"] = request.entityTag;
                    }
                    if (request.requestBody !== null) {
                        headers["Content-Type"] = "application/json";
                    }
                    const response = await fetch(request.path, {
                        method: request.method,
                        headers,
                        body: request.requestBody,
                        credentials: request.token === null ? "same-origin" : "omit"
                    });
                    const text = response.status === 204 ? "" : await response.text();
                    let body = null;
                    if (text.length > 0) {
                        try {
                            body = JSON.parse(text);
                        } catch (error) {
                            body = text;
                        }
                    }
                    return {
                        status: response.status,
                        contentType: response.headers.get("content-type"),
                        etag: response.headers.get("etag"),
                        wwwAuthenticate: response.headers.get("www-authenticate"),
                        body
                    };
                }
                """,
                request);
    }

    private static int responseStatus(Map<String, Object> response) {
        return ((Number) response.get("status")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseBody(Map<String, Object> response) {
        return (Map<String, Object>) response.get("body");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> responseList(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("body");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedBody(Map<String, Object> body, String propertyName) {
        return (Map<String, Object>) body.get(propertyName);
    }

    private static void assertProblemResponse(Map<String, Object> response, int expectedStatus) {
        assertEquals(expectedStatus, responseStatus(response));
        assertTrue(
                String.valueOf(response.get("contentType")).startsWith("application/problem+json"),
                "API errors should use application/problem+json.");
        assertEquals(expectedStatus, ((Number) responseBody(response).get("status")).intValue());
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
            Locator registerButton = page.locator("button:has-text('Register')");
            registerButton.click(
                    new Locator.ClickOptions().setTimeout(Duration.ofSeconds(60).toMillis()));
            page.locator("h1:has-text('My calendars')")
                    .or(page.locator(".ui-messages-error"))
                    .waitFor(new Locator.WaitForOptions()
                            .setTimeout(Duration.ofSeconds(60).toMillis()));
            return URI.create(page.url()).getPath();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "A concurrent disposable registration request failed.",
                    exception);
        }
    }

    private static String computedStyle(Locator element, String propertyName) {
        return (String) element.evaluate(
                "(element, propertyName) => getComputedStyle(element)[propertyName]",
                propertyName);
    }

    private static double contrastRatio(String firstColor, String secondColor) {
        double firstLuminance = relativeLuminance(firstColor);
        double secondLuminance = relativeLuminance(secondColor);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double relativeLuminance(String color) {
        Matcher colorComponents = Pattern.compile("\\d+").matcher(color);
        double[] linearComponents = new double[3];
        for (int componentIndex = 0; componentIndex < linearComponents.length; componentIndex++) {
            if (!colorComponents.find()) {
                throw new IllegalArgumentException("Expected an RGB color, but received " + color + ".");
            }
            double colorComponent = Integer.parseInt(colorComponents.group()) / 255.0;
            linearComponents[componentIndex] = colorComponent <= 0.04045
                    ? colorComponent / 12.92
                    : Math.pow((colorComponent + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linearComponents[0]
                + 0.7152 * linearComponents[1]
                + 0.0722 * linearComponents[2];
    }

    private static void assertHeaderUsesViewportWidth(Page page) {
        BoundingBox brandBounds = page.locator(".app-brand").boundingBox();
        BoundingBox navigationBounds = page.locator(".app-nav").boundingBox();
        Number viewportWidth = (Number) page.evaluate("window.innerWidth");
        assertTrue(
                brandBounds != null && navigationBounds != null,
                "The header brand and navigation should have visible bounds.");
        assertTrue(
                brandBounds.x <= 48,
                () -> "The header brand should use the viewport width, but its left edge was "
                        + brandBounds.x
                        + "px from the viewport edge.");
        double navigationRightGap =
                viewportWidth.doubleValue() - navigationBounds.x - navigationBounds.width;
        assertTrue(
                navigationRightGap <= 48,
                () -> "The header navigation should use the viewport width, but its right edge was "
                        + navigationRightGap
                        + "px from the viewport edge.");
    }

    private static void assertCalendarNavigationIcons(
            Locator previousMonthButton, Locator nextMonthButton) {
        assertTrue(
                Boolean.TRUE.equals(previousMonthButton.evaluate(
                        """
                        button => {
                            const iconStyles = getComputedStyle(button, "::before");
                            return iconStyles.content === '\"\"'
                                    && Number.parseFloat(iconStyles.width) >= 8
                                    && Number.parseFloat(iconStyles.height) >= 8
                                    && Number.parseFloat(iconStyles.borderRightWidth) >= 2
                                    && iconStyles.transform !== "none";
                        }
                        """)),
                "The previous-month button should have a visible leading arrow icon.");
        assertTrue(
                Boolean.TRUE.equals(nextMonthButton.evaluate(
                        """
                        button => {
                            const iconStyles = getComputedStyle(button, "::after");
                            return iconStyles.content === '\"\"'
                                    && Number.parseFloat(iconStyles.width) >= 8
                                    && Number.parseFloat(iconStyles.height) >= 8
                                    && Number.parseFloat(iconStyles.borderRightWidth) >= 2
                                    && iconStyles.transform !== "none";
                        }
                        """)),
                "The next-month button should have a visible trailing arrow icon.");
        assertFalse(
                previousMonthButton
                        .evaluate("button => getComputedStyle(button, '::before').transform")
                        .equals(nextMonthButton.evaluate(
                                "button => getComputedStyle(button, '::after').transform")),
                "The previous- and next-month arrows should point in opposite directions.");
    }

    private static void assertUnifiedButtonGeometry(Page page) {
        String mismatchSummary = (String) page.evaluate(
                """
                () => {
                    const renderedButtons = Array.from(document.querySelectorAll(
                            ".app-button, .ui-button:not(.ui-datepicker-trigger)"))
                            .filter(button => button.getClientRects().length > 0);
                    if (renderedButtons.length < 4) {
                        return `Expected at least four rendered buttons, found ${renderedButtons.length}.`;
                    }

                    const referenceStyles = getComputedStyle(renderedButtons[0]);
                    const expectedHeight = Number.parseFloat(referenceStyles.minHeight);
                    const sharedProperties = [
                        "borderRadius",
                        "borderWidth",
                        "fontFamily",
                        "fontSize",
                        "fontWeight",
                        "lineHeight",
                        "paddingLeft",
                        "paddingRight"
                    ];
                    const mismatches = [];

                    for (const button of renderedButtons) {
                        const buttonStyles = getComputedStyle(button);
                        const buttonLabel = button.value
                                || button.textContent.trim()
                                || button.getAttribute("aria-label")
                                || button.tagName;
                        const buttonHeight = button.getBoundingClientRect().height;
                        if (Math.abs(buttonHeight - expectedHeight) > 0.01) {
                            mismatches.push(
                                    `${buttonLabel} height ${buttonHeight}px != ${expectedHeight}px`);
                        }
                        for (const propertyName of sharedProperties) {
                            if (buttonStyles[propertyName] !== referenceStyles[propertyName]) {
                                mismatches.push(
                                        `${buttonLabel} ${propertyName} ${buttonStyles[propertyName]}`
                                        + ` != ${referenceStyles[propertyName]}`);
                            }
                        }
                    }
                    return mismatches.join("; ");
                }
                """);
        assertEquals(
                "",
                mismatchSummary,
                "Every standard application button should use the shared button geometry.");
    }

    private static void waitForElementAnimations(Locator element) {
        element.evaluate(
                "element => Promise.all("
                        + "element.getAnimations().map(animation => animation.finished))");
    }

    private static void assertMobileNavigationTargets(Page page) {
        for (Locator navigationTarget : page.locator(".app-brand, .app-nav a, .app-nav .app-button").all()) {
            BoundingBox bounds = navigationTarget.boundingBox();
            assertTrue(bounds != null, "Each mobile navigation target should have visible bounds.");
            assertTrue(
                    bounds.height >= 44,
                    () -> "Mobile navigation target '"
                            + navigationTarget.innerText()
                            + "' should be at least 44px tall, but was "
                            + bounds.height
                            + "px.");
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
            page.locator("#calendarPage")
                    .or(page.getByText(
                            "Invitation is invalid or no longer available.",
                            new Page.GetByTextOptions().setExact(true)))
                    .waitFor(new Locator.WaitForOptions()
                            .setTimeout(Duration.ofSeconds(60).toMillis()));
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
