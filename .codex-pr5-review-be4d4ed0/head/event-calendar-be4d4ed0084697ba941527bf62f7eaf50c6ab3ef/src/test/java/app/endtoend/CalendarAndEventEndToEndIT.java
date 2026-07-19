package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class CalendarAndEventEndToEndIT extends SharedCalendarEndToEndSupport {
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

            submitEventForm(page, "Invalid event " + uniqueSuffix, null, "2026-07-20 12:00", "2026-07-20 10:00", false);
            assertBodyContains(page, "Event end time must be after the start time.");
            assertEquals(0, page.locator("article", new Page.LocatorOptions().setHasText("Invalid event " + uniqueSuffix)).count());

            createEvent(page, eventTitle, "North landing", "2026-07-20 10:00", "2026-07-20 12:00", false);
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
            Locator firstDayInput = page.locator("input[id$='eventFirstDay_input']");
            Locator lastDayInput = page.locator("input[id$='eventLastDay_input']");
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

            createEvent(page, deletedEventTitle, null, "2026-07-21 14:00", "2026-07-21 15:00", false);
            Locator deletedEventRow = page.locator("article", new Page.LocatorOptions().setHasText(deletedEventTitle));
            deletedEventRow.locator("button:has-text('Delete')").click();
            confirmationButton(page, "Delete event").click();
            assertBodyContains(page, "Event deleted.");
            assertEquals(0, page.locator("article", new Page.LocatorOptions().setHasText(deletedEventTitle)).count());

            navigateToBearerLink(page, page.url());
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
            navigateToBearerLink(page, page.url());
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
            createEvent(page, eventTitle, "River", "2026-08-20 10:00", "2026-08-20 12:00", false);

            List<String> staleRegenerationBrowserMessages = new ArrayList<>();
            Page staleRegenerationPage = newPage(browserContext, staleRegenerationBrowserMessages);
            navigateToBearerLink(staleRegenerationPage, originalCalendarLink);
            assertBodyContains(staleRegenerationPage, "Regenerate link");

            calendarSettingsLink(page).click();
            page.locator("textarea[id$='calendarDescription']").fill(calendarDescription);
            page.locator("button:has-text('Save settings')").click();
            assertBodyContains(page, "Calendar settings saved.");
            page.locator("a:has-text('Back to calendar')").click();
            assertTrue(
                    originalCalendarLink.equals(page.url()),
                    "Member access must retain the same canonical calendar bearer link.");

            staleRegenerationPage.locator("button:has-text('Regenerate link')").click();
            clickAndWaitForNavigation(
                    staleRegenerationPage,
                    confirmationButton(staleRegenerationPage, "Regenerate link"),
                    "stale calendar-link regeneration");
            assertBodyContains(staleRegenerationPage, "Calendar link could not be regenerated.");
            assertBodyContains(
                    staleRegenerationPage,
                    "This calendar changed after you opened it. Reload the page and try again.");
            assertTrue(
                    originalCalendarLink.equals(staleRegenerationPage.url()),
                    () -> "A stale calendar page must remain on its canonical bearer link, but the browser was at "
                            + redactedDiagnosticUrl(staleRegenerationPage.url())
                            + ".");
            assertNoBrowserMessages(staleRegenerationBrowserMessages);
            staleRegenerationPage.close();

            try (BrowserContext publicBrowserContext = browser.newContext()) {
                List<String> publicBrowserMessages = new ArrayList<>();
                Page publicPage = newPage(publicBrowserContext, publicBrowserMessages);
                com.microsoft.playwright.Response publicResponse = navigateToBearerLink(publicPage, originalCalendarLink);
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
            clickAndWaitForNavigation(
                    page,
                    confirmationButton(page, "Regenerate link"),
                    "calendar-link regeneration");
            String regeneratedCalendarLink = page.url();
            assertTrue(
                    !originalCalendarLink.equals(regeneratedCalendarLink),
                    "Regeneration must replace the previous calendar bearer link.");
            assertCanonicalCalendarRoute(page, Long.toString(findCalendarId(calendarName)));

            try (BrowserContext regeneratedLinkBrowserContext = browser.newContext()) {
                List<String> oldLinkBrowserMessages = new ArrayList<>();
                Page oldLinkPage = newPage(regeneratedLinkBrowserContext, oldLinkBrowserMessages);
                assertEquals(404, navigateToBearerLink(oldLinkPage, originalCalendarLink).status());
                assertBodyContains(oldLinkPage, "Calendar link unavailable");
                assertBodyContains(oldLinkPage, "This calendar link no longer works.");
                assertBodyContains(oldLinkPage, "Ask a calendar member for the current link.");
                assertOnlyExpectedNotFoundNavigationMessage(oldLinkBrowserMessages);

                List<String> currentLinkBrowserMessages = new ArrayList<>();
                Page currentLinkPage = newPage(regeneratedLinkBrowserContext, currentLinkBrowserMessages);
                assertEquals(200, navigateToBearerLink(currentLinkPage, regeneratedCalendarLink).status());
                assertBodyContains(currentLinkPage, eventTitle);
                assertBodyContains(currentLinkPage, "Read-only");
                assertNoBrowserMessages(currentLinkBrowserMessages);
            }
            assertNoBrowserMessages(browserMessages);
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
            createEvent(ownerPage, eventTitle, null, "2026-11-01 10:00", "2026-11-01 11:00", false);
            calendarSettingsLink(ownerPage).click();

            assertEquals(200, navigateToBearerLink(publicPage, sharedCalendarLink).status());
            assertBodyContains(publicPage, eventTitle);

            setPublicAccess(ownerPage, false);
            assertBodyContains(ownerPage, "Public access disabled");
            navigateToBearerLink(ownerPage, ownerPage.url());
            assertFalse(ownerPage.getByLabel("Enable public read-only access").isChecked());
            assertEquals(404, navigateToBearerLink(publicPage, sharedCalendarLink).status());
            assertBodyContains(publicPage, "Calendar link unavailable");
            assertBodyContains(publicPage, "public access may be disabled");

            assertEquals(200, navigateToBearerLink(ownerPage, sharedCalendarLink).status());
            assertBodyContains(ownerPage, eventTitle);
            assertBodyContains(ownerPage, "Public access disabled");
            assertThat(ownerPage.locator("button:has-text('Create event')")).isVisible();
            calendarSettingsLink(ownerPage).click();

            setPublicAccess(ownerPage, true);
            assertBodyContains(ownerPage, "Public access enabled");
            navigateToBearerLink(ownerPage, ownerPage.url());
            assertTrue(ownerPage.getByLabel("Enable public read-only access").isChecked());
            assertEquals(200, navigateToBearerLink(publicPage, sharedCalendarLink).status());
            assertBodyContains(publicPage, eventTitle);

            setCalendarActive(Long.parseLong(calendarId), false);
            assertEquals(404, navigateToBearerLink(publicPage, sharedCalendarLink).status());
            assertEquals(404, navigateToBearerLink(ownerPage, sharedCalendarLink).status());
            assertBodyContains(ownerPage, "Calendar link unavailable");
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

            submitEventForm(page, "Equal times " + uniqueSuffix, null, "2026-12-01 10:00", "2026-12-01 10:00", false);
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

            createEvent(page, eventTitle, "Boundary location", "2026-12-02 10:00", "2026-12-02 11:00", false);
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
            navigateToBearerLink(page, page.url());
            assertBodyContains(page, eventTitle);
            assertThat(page.locator("article", new Page.LocatorOptions().setHasText(eventTitle)))
                    .containsText("Boundary location");

            for (String routePath : List.of("/app/calendar-settings", "/app/calendar-members")) {
                navigateToBearerLink(page, route(routePath));
                assertBodyContains(page, "Calendar not found");
                navigateToBearerLink(page, route(routePath + "?id=999999999"));
                assertBodyContains(page, "Calendar not found");
                navigateToBearerLink(page, route(routePath + "?id=not-a-number"));
                assertFalse(page.locator("body").innerText().contains("Exception thrown"));
            }
            navigateToBearerLink(page, calendarLink(calendarId));
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
        String staleTimeZoneEventTitle = "Stale time zone " + uniqueSuffix;
        seedUser(ownerUsername);
        String calendarId;

        try (BrowserContext setupContext = browser.newContext()) {
            Page setupPage = setupContext.newPage();
            signIn(setupPage, ownerUsername, TEST_PASSWORD);
            createCalendar(setupPage, calendarName);
            openCalendar(setupPage, calendarName);
            calendarId = Long.toString(findCalendarId(calendarName));
            createEvent(setupPage, eventTitle, null, "2026-12-10 10:00", "2026-12-10 11:00", false);
            assertBodyContains(setupPage, eventTitle);
            createEvent(setupPage, deletionEventTitle, null, "2026-12-11 10:00", "2026-12-11 11:00", false);
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
            navigateToBearerLink(firstPage, calendarLink(calendarId));
            navigateToBearerLink(secondPage, calendarLink(calendarId));
            navigateToBearerLink(staleDeletePage, calendarLink(calendarId));

            firstPage.locator("input[id$='eventTitle']").fill(staleTimeZoneEventTitle);
            firstPage.locator(".checkbox-field .ui-chkbox-box").click();
            assertThat(firstPage.getByLabel("All-day event")).isChecked();
            firstPage.locator("input[id$='eventFirstDay_input']").fill("2026-07-16");
            firstPage.locator("input[id$='eventLastDay_input']").fill("2026-07-16");
            navigateToBearerLink(secondPage, route("/app/calendar-settings?id=" + calendarId));
            secondPage.locator("input[id$='timeZone']").fill("Pacific/Honolulu");
            secondPage.locator("button:has-text('Save settings')").click();
            assertBodyContains(secondPage, "Calendar settings saved.");
            firstPage.locator("button:has-text('Create event')").click();
            assertBodyContains(
                    firstPage,
                    "This calendar changed after you opened the event form. Reload the page and try again.");
            assertEquals(
                    0,
                    queryLong("select count(*) from calendar_event where title = ?", staleTimeZoneEventTitle));
            navigateToBearerLink(firstPage, firstPage.url());
            navigateToBearerLink(secondPage, calendarLink(calendarId));

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
            navigateToBearerLink(secondPage, secondPage.url());
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
            confirmationButton(staleDeletePage, "Delete event").click();
            assertBodyContains(
                    staleDeletePage,
                    "This event changed after you opened it. Reload the page and try again.");
            navigateToBearerLink(staleDeletePage, staleDeletePage.url());
            assertBodyContains(staleDeletePage, deletionEventTitle + " updated");

            navigateToBearerLink(firstPage, route("/app/calendar-settings?id=" + calendarId));
            navigateToBearerLink(secondPage, route("/app/calendar-settings?id=" + calendarId));
            firstPage.locator("textarea[id$='calendarDescription']").fill("First settings update " + uniqueSuffix);
            firstPage.locator("button:has-text('Save settings')").click();
            assertBodyContains(firstPage, "Calendar settings saved.");
            secondPage.locator("textarea[id$='calendarDescription']").fill("Second settings update " + uniqueSuffix);
            secondPage.locator("button:has-text('Save settings')").click();
            assertBodyContains(secondPage, "This calendar changed after you opened it. Reload the page and try again.");
            navigateToBearerLink(secondPage, secondPage.url());
            assertBodyContains(secondPage, "First settings update " + uniqueSuffix);
            assertFalse(secondPage.locator("body").innerText().contains("Second settings update " + uniqueSuffix));
        }
    }

    @Test
    void invalidCalendarLinkReturnsClearNoindexNotFoundPageAndTheLegacyPrefixDoesNotRoute() {
        List<String> browserMessages = new ArrayList<>();
        try (BrowserContext browserContext = browser.newContext()) {
            Page page = newPage(browserContext, browserMessages);
            com.microsoft.playwright.Response response = navigateToBearerLink(page, route("/AAAAAAAAAAA"));
            assertEquals(404, response.status());
            assertAnonymousSessionCookie(response);
            assertEquals("Calendar link unavailable - Shared calendar", page.title());
            assertEquals("Calendar link unavailable", page.locator("h1").textContent().trim());
            assertEquals("noindex, nofollow", page.locator("meta[name='robots']").getAttribute("content"));
            assertBodyContains(page, "This calendar link no longer works.");
            assertBodyContains(page, "Ask a calendar member for the current link.");
            assertFalse(hasHorizontalOverflow(page));
            assertOnlyExpectedNotFoundNavigationMessage(browserMessages);

            List<String> legacyRouteBrowserMessages = new ArrayList<>();
            Page legacyRoutePage = newPage(browserContext, legacyRouteBrowserMessages);
            assertEquals(404, navigateToBearerLink(legacyRoutePage, route("/calendar/AAAAAAAAAAA")).status());
            assertOnlyExpectedNotFoundNavigationMessage(legacyRouteBrowserMessages);
        }
    }

}
