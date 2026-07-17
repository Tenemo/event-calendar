package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInfo;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SharedCalendarEndToEndSupport {
    static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9080";
    static final String APPLICATION_BASE_URL_PROPERTY = "app.baseUrl";
    static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE = "APP_BASE_URL";
    static final String HEALTH_URL_ENVIRONMENT_VARIABLE = "E2E_VERIFICATION_HEALTH_URL";
    static final String BROWSER_ENVIRONMENT_VARIABLE = "BROWSER";
    static final String POSTGRESQL_HOST_ENVIRONMENT_VARIABLE = "PGHOST";
    static final String POSTGRESQL_PORT_ENVIRONMENT_VARIABLE = "PGPORT";
    static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    static final String POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE = "PGPASSWORD";
    static final String PLAYWRIGHT_HEADED_ENVIRONMENT_VARIABLE = "PLAYWRIGHT_HEADED";
    static final String PLAYWRIGHT_HEADLESS_ENVIRONMENT_VARIABLE = "PLAYWRIGHT_HEADLESS";
    static final String SEEDED_PASSWORD_HASH_FOR_TEST_PASSWORD =
            "PBKDF2WithHmacSHA256:600000:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=:"
                    + "YTpMNBE5TiT//mxRmUMHckVy5XS82Y6oz0V8ZImb+/4=";
    static final String TEST_PASSWORD = "correct horse battery staple";
    static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(15);
    static final Duration APPLICATION_READY_POLL_INTERVAL = Duration.ofMillis(500);
    static final int[] RESPONSIVE_WIDTHS = {320, 390, 640, 768, 819, 820, 821, 1024, 1280};

    Playwright playwright;
    final BearerSecretRedactor bearerSecretRedactor = new BearerSecretRedactor();
    final EndToEndBrowserDiagnostics browserDiagnostics =
            new EndToEndBrowserDiagnostics(getClass());
    EndToEndBrowserDiagnostics.RecordedBrowser browser;
    URI applicationBaseUri;

    @BeforeAll
    void openBrowser() throws InterruptedException {
        applicationBaseUri = resolveApplicationBaseUri();
        waitForApplicationHealth();
        playwright = Playwright.create();
        browser = browserDiagnostics.launch(selectedBrowser(playwright), shouldRunHeadless());
    }

    @BeforeEach
    void prepareBrowserDiagnostics(TestInfo testInfo) {
        browserDiagnostics.startTest(testInfo.getTestMethod()
                .map(java.lang.reflect.Method::getName)
                .orElse(testInfo.getDisplayName()));
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

    String createRegistrationInvitation(Page page) {
        navigateToBearerLink(page, route("/app/invitations"));
        page.locator("button:has-text('Generate registration link')").click();
        Locator generatedInvitationLink = generatedInvitationLink(page);
        String invitationLink = generatedInvitationLink.inputValue();
        bearerSecretRedactor.rememberBearerValue(invitationLink);
        assertTrue(invitationLink.contains("/register?token="));
        return invitationLink;
    }

    Locator generatedInvitationLink(Page page) {
        Locator generatedInvitationLink = page.locator("input[id$='generatedInvitationLink']");
        assertThat(generatedInvitationLink).isVisible();
        String helpElementId = generatedInvitationLink.getAttribute("aria-describedby");
        assertEquals("generatedInvitationHelp", helpElementId);
        assertThat(page.locator("#" + helpElementId)).isVisible();
        bearerSecretRedactor.rememberBearerValue(generatedInvitationLink.inputValue());
        return generatedInvitationLink;
    }

    Locator invitationRow(Page page, String invitationLink) {
        bearerSecretRedactor.rememberBearerValue(invitationLink);
        Locator invitationRows = page.locator("tr");
        for (int rowIndex = 0; rowIndex < invitationRows.count(); rowIndex++) {
            Locator invitationRow = invitationRows.nth(rowIndex);
            Locator invitationLinkInput = invitationRow.locator("input.table-copy-field");
            if (invitationLinkInput.count() == 1
                    && invitationLink.equals(invitationLinkInput.inputValue())) {
                return invitationRow;
            }
        }
        return fail("Expected the invitation table to contain the requested bearer link.");
    }

    boolean invitationIsListed(Page page, String invitationLink) {
        bearerSecretRedactor.rememberBearerValue(invitationLink);
        Locator invitationLinkInputs = page.locator("input.table-copy-field");
        for (int inputIndex = 0; inputIndex < invitationLinkInputs.count(); inputIndex++) {
            if (invitationLink.equals(invitationLinkInputs.nth(inputIndex).inputValue())) {
                return true;
            }
        }
        return false;
    }

    Locator calendarSettingsLink(Page page) {
        return page.locator("a[href*='/app/calendar-settings']");
    }

    String createEditorInvitation(Page page, String calendarName) {
        navigateToBearerLink(page, route("/app/invitations"));
        Locator calendarSelect = page.locator("select[id$='calendar']");
        pressTabUntilFocused(page, calendarSelect, 12);
        assertVisibleOutline(calendarSelect);
        String calendarOptionValue = page.locator(
                        "select[id$='calendar'] option",
                        new Page.LocatorOptions().setHasText(calendarName))
                .getAttribute("value");
        calendarSelect.selectOption(calendarOptionValue);
        page.locator("button:has-text('Generate editor link')").click();
        String invitationLink = generatedInvitationLink(page).inputValue();
        bearerSecretRedactor.rememberBearerValue(invitationLink);
        assertTrue(invitationLink.contains("/register?token="));
        return invitationLink;
    }

    void registerNewUser(
            Page page,
            String invitationLink,
            String username,
            String displayName,
            String calendarName,
            String password) {
        navigateToBearerLink(page, invitationLink);
        fillRegistrationForm(page, username, displayName, calendarName, password);
        page.locator("button:has-text('Register')").click();
        page.waitForURL("**/app/calendars");
    }

    void fillRegistrationForm(
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

    void signIn(Page page, String username, String password) {
        navigateToBearerLink(page, route("/login"));
        submitSignInAndWaitForUrl(page, username, password, "**/app/calendars");
    }

    void submitSignInAndWaitForUrl(
            Page page,
            String username,
            String password,
            String expectedUrlPattern) {
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='password']").fill(password);
        page.locator("button:has-text('Sign in')").click();
        try {
            page.waitForURL(expectedUrlPattern);
            waitForPageResources(page);
        } catch (RuntimeException exception) {
            fail("Sign-in did not reach the expected application route. The browser remained at "
                    + redactedDiagnosticUrl(page.url())
                    + " with body: "
                    + abbreviate(page.locator("body").innerText())
                    + " ("
                    + exception.getClass().getSimpleName()
                    + ").");
        }
    }

    void signOut(Page page) {
        page.locator("input[value='Sign out']").click();
        page.waitForURL(route("/"));
        waitForPageResources(page);
    }

    void fillPasswordChangeForm(
            Page page,
            String currentPassword,
            String newPassword,
            String confirmation) {
        page.locator("input[id$='currentPassword']").fill(currentPassword);
        page.locator("input[id$='newPassword']").fill(newPassword);
        page.locator("input[id$='newPasswordConfirmation']").fill(confirmation);
    }

    void createCalendar(Page page, String calendarName) {
        page.locator("input[id$='calendarName']").fill(calendarName);
        page.locator("button:has-text('Create calendar')").click();
        assertBodyContains(page, calendarName);
    }

    void openCalendar(Page page, String calendarName) {
        if (!URI.create(page.url()).getPath().equals("/app/calendars")) {
            navigateToBearerLink(page, route("/app/calendars"));
        }
        page.locator("a", new Page.LocatorOptions().setHasText(calendarName)).first().click();
        waitForCanonicalCalendarRoute(page);
    }

    void createEvent(
            Page page,
            String title,
            String location,
            String startTime,
            String endTime,
            boolean allDay) {
        submitEventForm(page, title, location, startTime, endTime, allDay);
        assertBodyContains(page, title);
    }

    void submitEventForm(
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
            Locator firstDayInput = page.locator("input[id$='eventFirstDay_input']");
            Locator lastDayInput = page.locator("input[id$='eventLastDay_input']");
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

    void enterEventWithDescription(
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

    void assertRegistrationRejected(
            Page page,
            String invitationLink,
            String username,
            String password,
            String expectedMessage) {
        navigateToBearerLink(page, invitationLink);
        fillRegistrationForm(page, username, "Rejected user", "Rejected calendar", password);
        page.locator("button:has-text('Register')").click();
        assertBodyContains(page, expectedMessage);
    }

    void setPublicAccess(Page page, boolean enabled) {
        Locator publicAccessCheckbox = page.getByLabel("Enable public read-only access");
        if (publicAccessCheckbox.isChecked() != enabled) {
            page.locator(".checkbox-field .ui-chkbox-box").click();
        }
        page.locator("button:has-text('Save settings')").click();
        assertBodyContains(page, enabled ? "Public access enabled" : "Public access disabled");
    }

    void setRawInputValue(Locator input, String value) {
        input.evaluate(
                "(element, rawValue) => { element.removeAttribute('maxlength'); element.value = rawValue; }",
                value);
    }

    void clickWithoutChangingFocus(Locator button) {
        button.evaluate("element => element.click()");
    }

    void scheduleClickAt(Locator button, long epochMilliseconds) {
        button.evaluate(
                "(element, clickTime) => window.setTimeout("
                        + "() => element.click(), Math.max(0, Number(clickTime) - Date.now()))",
                Long.toString(epochMilliseconds));
    }

    void waitForInvitationAcceptanceResult(Page page) {
        page.waitForFunction(
                "() => /^\\/[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$/.test(location.pathname) "
                        + "|| document.body.innerText.includes('Invitation is invalid or no longer available.')");
    }

    void waitForCanonicalCalendarRoute(Page page) {
        page.waitForFunction("() => /^\\/[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$/.test(location.pathname)");
    }

    void waitForMembershipChangeResult(Page page) {
        page.waitForFunction(
                "() => document.body.innerText.includes('Member role saved.') "
                        + "|| document.body.innerText.includes('Admin access is required.')");
    }

    Locator visibleConfirmationDialog(Page page) {
        return page.locator(".ui-confirm-dialog:visible");
    }

    Locator confirmationButton(Page page, String buttonLabel) {
        return visibleConfirmationDialog(page)
                .locator("button", new Locator.LocatorOptions().setHasText(buttonLabel));
    }

    void assertFocusRemainsWithin(Locator container) {
        assertEquals(
                true,
                container.evaluate("element => element.contains(document.activeElement)"),
                "Keyboard focus should remain inside the confirmation dialog.");
    }

    void pressTabUntilFocused(Page page, Locator target, int maximumTabPresses) {
        for (int tabPress = 0; tabPress < maximumTabPresses; tabPress++) {
            page.keyboard().press("Tab");
            if (Boolean.TRUE.equals(target.evaluate("element => document.activeElement === element"))) {
                return;
            }
        }
        fail("The target control was not reachable within " + maximumTabPresses + " Tab presses.");
    }

    void assertVisibleOutline(Locator locator) {
        assertEquals(
                true,
                locator.evaluate(
                        "element => { const style = getComputedStyle(element); "
                                + "return style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0; }"),
                "The focused control should have a visible outline.");
        assertMinimumContrast(locator, "outlineColor", true, 3.0, "focus outline");
    }

    void assertNoAutomaticAccessibilityViolations(Page page, String pageDescription) {
        AxeResults accessibilityResults = new AxeBuilder(page)
                .withTags(List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22a", "wcag22aa"))
                .analyze();
        assertTrue(
                accessibilityResults.violationFree(),
                () -> "Expected no automatic WCAG A or AA violations on the " + pageDescription + " at "
                        + redactedDiagnosticUrl(page.url()) + ", but found: "
                        + accessibilityResults.getViolations().stream()
                                .map(violation -> automaticAccessibilityViolationDescription(page, violation))
                                .toList());
    }

    String automaticAccessibilityViolationDescription(Page page, Rule violation) {
        String listStructure = "list".equals(violation.getId())
                ? "; list structures=" + page.locator("ul").evaluateAll(
                        "lists => lists.slice(0, 5).map(list => ({"
                                + "className: list.className, "
                                + "directChildren: Array.from(list.children, child => ({"
                                + "tagName: child.tagName.toLowerCase(), className: child.className, "
                                + "role: child.getAttribute('role')"
                                + "}))"
                                + "}))")
                : "";
        return bearerSecretRedactor.redact(
                violation.getId() + " (" + violation.getImpact() + "): " + violation.getHelp()
                        + "; targets="
                        + violation.getNodes().stream()
                                .limit(5)
                                .map(node -> String.valueOf(node.getTarget()))
                                .toList()
                        + listStructure);
    }

    void assertTextContrast(Locator locator, double minimumContrastRatio) {
        assertMinimumContrast(locator, "color", false, minimumContrastRatio, "text");
    }

    void assertControlBoundaryContrast(Locator locator, double minimumContrastRatio) {
        assertMinimumContrast(locator, "borderTopColor", false, minimumContrastRatio, "control boundary");
    }

    void assertMinimumContrast(
            Locator locator,
            String foregroundProperty,
            boolean compareWithParentBackground,
            double minimumContrastRatio,
            String contrastPurpose) {
        double measuredContrastRatio = ((Number) locator.evaluate(
                        "(element, settings) => {"
                                + "const parseColor = value => {"
                                + "const channels = value.match(/[\\d.]+/g).map(Number);"
                                + "return [channels[0], channels[1], channels[2], channels.length > 3 ? channels[3] : 1];"
                                + "};"
                                + "const luminance = color => {"
                                + "const channels = color.slice(0, 3).map(channel => {"
                                + "const normalized = channel / 255;"
                                + "return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);"
                                + "});"
                                + "return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];"
                                + "};"
                                + "const style = getComputedStyle(element);"
                                + "const foreground = parseColor(style[settings[0]]);"
                                + "let backgroundElement = settings[1] ? element.parentElement : element;"
                                + "let background = [255, 255, 255, 0];"
                                + "while (backgroundElement) {"
                                + "background = parseColor(getComputedStyle(backgroundElement).backgroundColor);"
                                + "if (background[3] > 0) break;"
                                + "backgroundElement = backgroundElement.parentElement;"
                                + "}"
                                + "const foregroundLuminance = luminance(foreground);"
                                + "const backgroundLuminance = luminance(background);"
                                + "const lighter = Math.max(foregroundLuminance, backgroundLuminance);"
                                + "const darker = Math.min(foregroundLuminance, backgroundLuminance);"
                                + "return (lighter + 0.05) / (darker + 0.05);"
                                + "}",
                        List.of(foregroundProperty, compareWithParentBackground)))
                .doubleValue();
        assertTrue(
                measuredContrastRatio >= minimumContrastRatio,
                () -> "Expected " + contrastPurpose + " contrast of at least " + minimumContrastRatio
                        + ":1, but measured " + measuredContrastRatio + ":1.");
    }

    void assertRegionsHaveAccessibleNames(Page page) {
        Locator regions = page.locator("[role='region']");
        for (int regionIndex = 0; regionIndex < regions.count(); regionIndex++) {
            Locator region = regions.nth(regionIndex);
            String accessibleLabel = region.getAttribute("aria-label");
            String labelledBy = region.getAttribute("aria-labelledby");
            assertTrue(
                    accessibleLabel != null && !accessibleLabel.isBlank()
                            || labelledBy != null && !labelledBy.isBlank(),
                    "Every region should have an accessible name.");
            if (labelledBy != null && !labelledBy.isBlank()) {
                assertTrue(
                        page.locator("[id='" + labelledBy + "']").count() > 0,
                        () -> "The region label target '" + labelledBy + "' should exist.");
            }
        }
    }

    void assertControlCanBeBroughtIntoView(
            Page page, Locator control, int viewportWidth, int viewportHeight) {
        control.scrollIntoViewIfNeeded();
        assertThat(control).isVisible();
        assertEquals(
                true,
                control.evaluate(
                        "(element, viewport) => {"
                                + "const bounds = element.getBoundingClientRect();"
                                + "const centerX = Math.max(0, Math.min(viewport[0] - 1, bounds.left + bounds.width / 2));"
                                + "const centerY = Math.max(0, Math.min(viewport[1] - 1, bounds.top + bounds.height / 2));"
                                + "const elementAtCenter = document.elementFromPoint(centerX, centerY);"
                                + "return bounds.left >= 0 && bounds.right <= viewport[0] "
                                + "&& bounds.top >= 0 && bounds.bottom <= viewport[1] "
                                + "&& elementAtCenter && (element.contains(elementAtCenter) || elementAtCenter.contains(element));"
                                + "}",
                        List.of(viewportWidth, viewportHeight)),
                "The control should be fully visible and unobscured after scrolling it into view.");
    }

    void assertResponsiveTableRegion(Page page, String accessibleLabel, int viewportWidth) {
        Locator tableRegion = page.locator(".table-scroll-region[aria-label='" + accessibleLabel + "']");
        tableRegion.focus();
        assertEquals(true, tableRegion.evaluate("element => document.activeElement === element"));
        assertVisibleOutline(tableRegion);
        boolean hasInternalOverflow = Boolean.TRUE.equals(
                tableRegion.evaluate("element => element.scrollWidth > element.clientWidth"));
        if (hasInternalOverflow) {
            tableRegion.evaluate("element => element.scrollLeft = 0");
            page.keyboard().press("ArrowRight");
            page.waitForFunction(
                    "element => element.scrollLeft > 0",
                    tableRegion.elementHandle(),
                    new Page.WaitForFunctionOptions().setTimeout(5_000));
            assertTrue(
                    ((Number) tableRegion.evaluate("element => element.scrollLeft")).doubleValue() > 0,
                    accessibleLabel + " should be keyboard-scrollable at " + viewportWidth + " pixels.");
        }
    }

    Page newPage(BrowserContext browserContext, List<String> browserMessages) {
        Page page = browserContext.newPage();
        page.onConsoleMessage(message -> {
            if (message.type().equals("error") || message.type().equals("warning")) {
                browserMessages.add(bearerSecretRedactor.redact(
                        message.type() + ": " + message.text()));
            }
        });
        page.onPageError(error -> browserMessages.add(
                bearerSecretRedactor.redact("page error: " + error)));
        page.onRequestFailed(request -> {
            if (!request.resourceType().equals("document")) {
                browserMessages.add(bearerSecretRedactor.redact(
                        "failed " + request.method() + " " + request.resourceType() + " request: "
                                + redactedDiagnosticUrl(request.url()) + " (" + request.failure()
                                + ") while the page was at " + redactedDiagnosticUrl(page.url())));
            }
        });
        page.onResponse(response -> {
            if (response.status() >= 400 && !response.request().resourceType().equals("document")) {
                browserMessages.add(bearerSecretRedactor.redact(
                        "HTTP " + response.status() + " for "
                                + response.request().resourceType() + " resource: "
                                + redactedDiagnosticUrl(response.url())));
            }
        });
        return page;
    }

    void waitForApplicationHealth() throws InterruptedException {
        URI healthUri = healthUri();
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

    boolean isDockerComposeManagedEndToEndEnvironment() {
        String managedApplicationBaseUrl = System.getenv("E2E_VERIFICATION_BASE_URL");
        return managedApplicationBaseUrl != null
                && !managedApplicationBaseUrl.isBlank()
                && removeTrailingSlashes(managedApplicationBaseUrl.trim())
                        .equals(removeTrailingSlashes(applicationBaseUri.toString()));
    }

    void waitForApplicationToBecomeUnavailable() throws InterruptedException {
        URI healthUri = healthUri();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || !response.body().trim().equals("ok")) {
                    return;
                }
            } catch (IOException exception) {
                return;
            }
            Thread.sleep(APPLICATION_READY_POLL_INTERVAL.toMillis());
        }
        fail("The isolated application remained healthy after its Docker Compose service was stopped.");
    }

    void waitForHealthResponse(int expectedStatus, String expectedBody, Duration timeout)
            throws InterruptedException {
        URI healthUri = healthUri();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        String lastHealthCheckResult = "no response";
        while (System.nanoTime() < deadlineNanos) {
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(healthUri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                String responseBody = response.body().trim();
                if (response.statusCode() == expectedStatus && responseBody.equals(expectedBody)) {
                    return;
                }
                lastHealthCheckResult = "HTTP " + response.statusCode() + " with body '"
                        + abbreviate(responseBody) + "'";
            } catch (IOException exception) {
                lastHealthCheckResult = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }
            Thread.sleep(APPLICATION_READY_POLL_INTERVAL.toMillis());
        }
        fail("Health endpoint " + healthUri + " did not return HTTP " + expectedStatus + " with body '"
                + expectedBody + "' within " + timeout.toSeconds() + " seconds. Last result: "
                + lastHealthCheckResult + ".");
    }

    void runDockerComposeCommand(String description, String... composeArguments)
            throws IOException, InterruptedException {
        Path projectDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(projectDirectory.resolve("docker-compose.yml"))) {
            fail("Could not run " + description + " because docker-compose.yml was not found in "
                    + projectDirectory + ".");
        }

        List<String> command = new ArrayList<>(List.of(
                "docker",
                "compose",
                "--profile",
                "e2e-verification"));
        command.addAll(List.of(composeArguments));
        Process process = new ProcessBuilder(command)
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out while trying to " + description + ".");
        }
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            fail("Could not " + description + "; Docker Compose exited with " + process.exitValue()
                    + ". Output: " + abbreviate(processOutput));
        }
    }

    URI resolveApplicationBaseUri() {
        return URI.create(removeTrailingSlashes(firstNonBlank(
                System.getProperty(APPLICATION_BASE_URL_PROPERTY),
                System.getenv(APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE),
                DEFAULT_APPLICATION_BASE_URL)));
    }

    URI healthUri() {
        String configuredHealthUrl = System.getenv(HEALTH_URL_ENVIRONMENT_VARIABLE);
        if (configuredHealthUrl != null && !configuredHealthUrl.isBlank()) {
            return URI.create(configuredHealthUrl.trim());
        }
        return URI.create(removeTrailingSlashes(applicationBaseUri.toString()) + "/health");
    }

    Connection openDatabaseConnection() throws SQLException {
        String jdbcUrl = "jdbc:postgresql://"
                + firstNonBlank(System.getenv(POSTGRESQL_HOST_ENVIRONMENT_VARIABLE), "localhost")
                + ":"
                + firstNonBlank(System.getenv(POSTGRESQL_PORT_ENVIRONMENT_VARIABLE), "5432")
                + "/"
                + firstNonBlank(System.getenv(POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE), "calendar");
        return DriverManager.getConnection(
                jdbcUrl,
                firstNonBlank(System.getenv(POSTGRESQL_USER_ENVIRONMENT_VARIABLE), "calendar"),
                firstNonBlank(System.getenv(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE), "calendar"));
    }

    void seedUser(String username) throws SQLException {
        seedUser(username, "End-to-end user", true);
    }

    void seedUser(String username, String displayName, boolean active) throws SQLException {
        try (Connection connection = openDatabaseConnection();
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

    String insertExpiredInvitation(String creatorUsername) throws SQLException {
        String invitationToken = "expired-test-" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into app_invitation "
                                + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at, created_at) "
                                + "select ?, null, null, id, now() - interval '1 minute', now() "
                                + "from app_user where username = ?")) {
            statement.setString(1, invitationToken);
            statement.setString(2, creatorUsername);
            assertEquals(1, statement.executeUpdate(), "Expected one expired invitation to be inserted for test setup.");
        }
        String invitationLink = route("/register?token=" + invitationToken);
        bearerSecretRedactor.rememberBearerValue(invitationLink);
        return invitationLink;
    }

    void insertRegistrationInvitations(String creatorUsername, int invitationCount) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into app_invitation "
                                + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at, created_at) "
                                + "select ?, null, null, app_user.id, "
                                + "fixture.created_at + interval '7 days', fixture.created_at "
                                + "from app_user cross join "
                                + "(select now() - (? * interval '1 second') as created_at) fixture "
                                + "where app_user.username = ?")) {
            for (int invitationIndex = 0; invitationIndex < invitationCount; invitationIndex++) {
                statement.setString(1, "pagination-test-" + UUID.randomUUID().toString().replace("-", ""));
                statement.setInt(2, invitationIndex);
                statement.setString(3, creatorUsername);
                statement.addBatch();
            }
            for (int updateCount : statement.executeBatch()) {
                assertEquals(1, updateCount, "Expected each paginated invitation fixture to be inserted.");
            }
        }
    }

    void assertInvitationLifetimeConstraintRejectsLongerInvitation(String creatorUsername)
            throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into app_invitation "
                                + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at, created_at) "
                                + "select ?, null, null, id, now() + interval '8 days', now() "
                                + "from app_user where username = ?")) {
            statement.setString(1, "overlong-test-" + UUID.randomUUID().toString().replace("-", ""));
            statement.setString(2, creatorUsername);
            try {
                statement.executeUpdate();
                fail("The database accepted an invitation whose lifetime exceeded seven days.");
            } catch (SQLException exception) {
                assertEquals("23514", exception.getSQLState());
            }
        }
    }

    void setCalendarActive(long calendarId, boolean active) throws SQLException {
        executeDatabaseUpdate("update calendar set active = ? where id = ?", active, calendarId);
    }

    void setUserActive(String username, boolean active) throws SQLException {
        executeDatabaseUpdate("update app_user set active = ? where username = ?", active, username);
    }

    long findCalendarId(String calendarName) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select id from calendar where name = ? order by id desc limit 1")) {
            statement.setString(1, calendarName);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), () -> "Expected calendar '" + calendarName + "' to exist.");
                return resultSet.getLong(1);
            }
        }
    }

    String calendarLink(String calendarId) throws SQLException {
        long numericCalendarId = Long.parseLong(calendarId);
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select public_token from calendar where id = ?")) {
            statement.setLong(1, numericCalendarId);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), () -> "Expected calendar " + calendarId + " to exist.");
                String calendarLink = route("/" + resultSet.getString(1));
                bearerSecretRedactor.rememberBearerValue(calendarLink);
                return calendarLink;
            }
        }
    }

    long countActiveAdmins(long calendarId) throws SQLException {
        try (Connection connection = openDatabaseConnection();
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

    void executeDatabaseUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
                statement.setObject(parameterIndex + 1, parameters[parameterIndex]);
            }
            assertEquals(1, statement.executeUpdate(), "Expected one database record to be updated for test setup.");
        }
    }

    long queryLong(String sql, Object... parameters) throws SQLException {
        try (Connection connection = openDatabaseConnection();
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

    BrowserType selectedBrowser(Playwright playwright) {
        String browserName = firstNonBlank(System.getenv(BROWSER_ENVIRONMENT_VARIABLE), "chromium")
                .toLowerCase(Locale.ROOT);
        return switch (browserName) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> fail("Unsupported Playwright browser '" + browserName + "'. Use chromium, firefox, or webkit.");
        };
    }

    boolean shouldRunHeadless() {
        if (Boolean.parseBoolean(firstNonBlank(System.getenv(PLAYWRIGHT_HEADED_ENVIRONMENT_VARIABLE), "false"))) {
            return false;
        }
        return Boolean.parseBoolean(firstNonBlank(System.getenv(PLAYWRIGHT_HEADLESS_ENVIRONMENT_VARIABLE), "true"));
    }

    String route(String path) {
        return removeTrailingSlashes(applicationBaseUri.toString()) + path;
    }

    Response navigateToBearerLink(Page page, String link) {
        rememberBearerLinkIfPresent(link);
        try {
            return page.navigate(link);
        } catch (RuntimeException exception) {
            return fail(
                    "Browser navigation failed for " + redactedDiagnosticUrl(link) + " ("
                            + exception.getClass().getSimpleName() + ").");
        }
    }

    void assertCanonicalCalendarRoute(Page page, String calendarId) throws SQLException {
        URI currentUri = URI.create(page.url());
        URI expectedUri = URI.create(calendarLink(calendarId));
        assertTrue(
                expectedUri.getPath().equals(currentUri.getPath()),
                () -> "Unexpected calendar URL " + redactedDiagnosticUrl(page.url()) + ".");
        assertTrue(
                currentUri.getRawQuery() == null,
                () -> "The canonical calendar URL must not contain a query string: "
                        + redactedDiagnosticUrl(page.url()));
        assertTrue(
                isCanonicalCalendarPath(page.url()),
                () -> "Expected a token-based calendar URL, but saw "
                        + redactedDiagnosticUrl(page.url()) + ".");
    }

    boolean isCanonicalCalendarPath(String url) {
        String path = URI.create(url).getPath();
        return path.matches("/[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]");
    }

    void assertBodyContains(Page page, String expectedText) {
        try {
            assertThat(page.locator("body"))
                    .containsText(expectedText, new LocatorAssertions.ContainsTextOptions().setTimeout(5_000));
            waitForPageResources(page);
        } catch (AssertionError assertionError) {
            String bodyText = page.locator("body").innerText();
            fail("Expected page body to contain '" + bearerSecretRedactor.redact(expectedText) + "' at "
                    + redactedDiagnosticUrl(page.url()) + " with title '"
                    + bearerSecretRedactor.redact(page.title()) + "', but body was: "
                    + abbreviate(bodyText) + " (" + assertionError.getClass().getSimpleName() + ").");
        }
    }

    void assertNoBrowserMessages(List<String> browserMessages) {
        assertTrue(
                browserMessages.isEmpty(),
                () -> "Expected no browser console errors or warnings, but saw: "
                        + bearerSecretRedactor.redact(browserMessages.toString()));
    }

    void waitForPageResources(Page page) {
        page.waitForFunction(
                "() => (typeof PrimeFaces === 'undefined' || !PrimeFaces.ajax "
                        + "|| (PrimeFaces.ajax.Queue.isEmpty() && PrimeFaces.ajax.Queue.xhrs.length === 0)) "
                        + "&& (!document.fonts || document.fonts.status === 'loaded')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(5_000));
    }

    void clickAndWaitForNavigation(Page page, Locator locator, String actionDescription) {
        try {
            locator.click();
            waitForPageResources(page);
        } catch (RuntimeException exception) {
            fail("Browser navigation for "
                    + bearerSecretRedactor.redact(actionDescription)
                    + " failed ("
                    + exception.getClass().getSimpleName()
                    + ").");
        }
    }

    void assertRollingSessionCookie(Response response) {
        String sessionCookieHeader = response.headerValues("set-cookie").stream()
                .filter(header -> header.contains("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Authenticated activity did not refresh the session cookie."));
        assertTrue(sessionCookieHeader.contains("Path=/"));
        assertTrue(sessionCookieHeader.contains("Secure"));
        assertTrue(sessionCookieHeader.contains("HttpOnly"));
        assertTrue(sessionCookieHeader.contains("SameSite=Lax"));
        assertTrue(
                sessionCookieHeader.contains("Max-Age=2592000")
                        || sessionCookieHeader.contains("Expires="),
                "Refreshed session cookie did not have a persistent 30-day lifetime.");
    }

    void assertAnonymousSessionCookie(Response response) {
        String sessionCookieHeader = response.headerValues("set-cookie").stream()
                .filter(header -> header.contains("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Anonymous request did not receive a session cookie."));
        assertTrue(sessionCookieHeader.contains("Path=/"));
        assertTrue(sessionCookieHeader.contains("Secure"));
        assertTrue(sessionCookieHeader.contains("HttpOnly"));
        assertTrue(sessionCookieHeader.contains("SameSite=Lax"));
        assertFalse(sessionCookieHeader.contains("Max-Age="));
        assertFalse(sessionCookieHeader.contains("Expires="));
    }

    String requiredSessionCookieValue(BrowserContext browserContext) {
        return browserContext.cookies(route("/")).stream()
                .filter(cookie -> "JSESSIONID".equals(cookie.name))
                .map(cookie -> cookie.value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a JSESSIONID cookie."));
    }

    void assertOnlyExpectedNotFoundNavigationMessage(List<String> browserMessages) {
        assertTrue(
                browserMessages.size() <= 1
                        && browserMessages.stream().allMatch(message -> message.contains("status of 404")),
                () -> "Expected only the browser's failed-navigation message for the intentional 404, but saw: "
                        + browserMessages);
    }

    void assertOnlyExpectedNotFoundNavigationMessages(List<String> browserMessages) {
        assertTrue(
                !browserMessages.isEmpty()
                        && browserMessages.stream().allMatch(message -> message.contains("status of 404")),
                () -> "Expected only intentional 404 navigation messages, but saw: " + browserMessages);
    }

    boolean hasHorizontalOverflow(Page page) {
        return Boolean.TRUE.equals(page.evaluate(
                "() => document.documentElement.scrollWidth > document.documentElement.clientWidth"));
    }

    String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("At least one non-blank value is required.");
    }

    String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }

    String redactedDiagnosticUrl(String value) {
        return bearerSecretRedactor.redactUrl(value);
    }

    String abbreviate(String value) {
        String normalizedValue = bearerSecretRedactor.redact(value).replaceAll("\\s+", " ").trim();
        return normalizedValue.length() <= 240 ? normalizedValue : normalizedValue.substring(0, 240) + "...";
    }

    private void rememberBearerLinkIfPresent(String link) {
        if (link == null) {
            return;
        }
        if (link.contains("?token=")
                || link.contains("&token=")
                || link.contains("?invite=")
                || link.contains("&invite=")
                || isCanonicalCalendarPath(link)) {
            bearerSecretRedactor.rememberBearerValue(link);
        }
    }
}
