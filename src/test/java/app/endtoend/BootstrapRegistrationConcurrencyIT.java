package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class BootstrapRegistrationConcurrencyIT {
    private static final String APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_BASE_URL";
    private static final String DATABASE_PORT_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_DATABASE_PORT";
    private static final String INVITATION_TOKEN_ENVIRONMENT_VARIABLE =
            "BOOTSTRAP_VERIFICATION_INVITATION_TOKEN";
    private static final String DEFAULT_APPLICATION_BASE_URL = "http://localhost:9081";
    private static final String DEFAULT_DATABASE_PORT = "55432";
    private static final String DEFAULT_INVITATION_TOKEN =
            "bootstrap-verification-only-token-00000000000000000000000000000000";
    private static final String DATABASE_NAME = "calendar_bootstrap_verification";
    private static final String DATABASE_USER = "calendar_bootstrap_verification";
    private static final String DATABASE_PASSWORD = "calendar_bootstrap_verification";
    private static final String EXPECTED_LATEST_FLYWAY_VERSION = "11";
    private static final String VALID_PASSWORD = "Bootstrap password 2026";
    private static final Duration APPLICATION_READY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration BLOCKED_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private URI applicationBaseUri;
    private String bootstrapInvitationToken;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void prepareIsolatedApplication() throws Exception {
        applicationBaseUri = URI.create(removeTrailingSlashes(environmentValueOrDefault(
                APPLICATION_BASE_URL_ENVIRONMENT_VARIABLE,
                DEFAULT_APPLICATION_BASE_URL)));
        bootstrapInvitationToken = environmentValueOrDefault(
                INVITATION_TOKEN_ENVIRONMENT_VARIABLE,
                DEFAULT_INVITATION_TOKEN);
        waitForApplicationHealth();
        assertFreshMigratedDatabase();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
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
    void failedClaimRollsBackBeforeConcurrentRegistrationsCreateExactlyOneAccount() throws Exception {
        submitRegistrationThatFailsAfterClaim();
        assertAllBootstrapDataWasRolledBack();

        String firstUsername = "bootstrap-first";
        String secondUsername = "bootstrap-second";
        try (BrowserContext firstBrowserContext = browser.newContext();
                BrowserContext secondBrowserContext = browser.newContext();
                Connection bootstrapRowLockConnection = databaseConnection()) {
            Page firstPage = preparedRegistrationPage(
                    firstBrowserContext,
                    firstUsername,
                    "First bootstrap user",
                    "First bootstrap calendar");
            Page secondPage = preparedRegistrationPage(
                    secondBrowserContext,
                    secondUsername,
                    "Second bootstrap user",
                    "Second bootstrap calendar");

            bootstrapRowLockConnection.setAutoCommit(false);
            lockBootstrapRow(bootstrapRowLockConnection);
            long scheduledSubmissionTime = System.currentTimeMillis() + 500;
            scheduleRegistration(firstPage, scheduledSubmissionTime);
            scheduleRegistration(secondPage, scheduledSubmissionTime);
            waitForBlockedBootstrapClaims(2);
            bootstrapRowLockConnection.commit();

            waitForRegistrationResult(firstPage);
            waitForRegistrationResult(secondPage);
            boolean firstRegistrationSucceeded = registrationSucceeded(firstPage);
            boolean secondRegistrationSucceeded = registrationSucceeded(secondPage);
            assertNotEquals(
                    firstRegistrationSucceeded,
                    secondRegistrationSucceeded,
                    "Exactly one simultaneous bootstrap registration must succeed.");

            Page rejectedPage = firstRegistrationSucceeded ? secondPage : firstPage;
            assertThat(rejectedPage.locator("body"))
                    .containsText("Invitation is invalid or no longer available.");

            String successfulUsername = firstRegistrationSucceeded ? firstUsername : secondUsername;
            assertEquals(1L, queryLong("select count(*) from app_user"));
            assertEquals(successfulUsername, queryString("select username from app_user"));
            assertEquals(1L, queryLong("select count(*) from calendar"));
            assertTrue(queryBoolean(
                    "select public_token ~ '^[A-Za-z0-9_-]{10}[AEIMQUYcgkosw048]$' from calendar"));
            assertEquals(1L, queryLong("select count(*) from calendar_member"));
            assertEquals(
                    1L,
                    queryLong("select count(*) from calendar_member "
                            + "where active = true and role_name = 'ADMIN'"));
            assertTrue(queryBoolean(
                    "select consumed_at is not null from app_registration_bootstrap where singleton_id = 1"));
        }
    }

    private void submitRegistrationThatFailsAfterClaim() {
        try (BrowserContext browserContext = browser.newContext()) {
            Page page = browserContext.newPage();
            page.navigate(registrationUrl());
            page.locator("input[id$='username']").fill("bootstrap-failed");
            page.locator("input[id$='displayName']").fill("Failed bootstrap user");
            page.locator("input[id$='calendarName']").fill("Failed bootstrap calendar");
            page.locator("input[id$='password']").fill("Short1");
            page.locator("button:has-text('Register')").click();
            assertThat(page.locator("body")).containsText("Password must be at least 8 characters.");
        }
    }

    private void assertAllBootstrapDataWasRolledBack() throws SQLException {
        assertEquals(0L, queryLong("select count(*) from app_user"));
        assertEquals(0L, queryLong("select count(*) from calendar"));
        assertEquals(0L, queryLong("select count(*) from calendar_member"));
        assertFalse(queryBoolean(
                "select consumed_at is not null from app_registration_bootstrap where singleton_id = 1"));
    }

    private Page preparedRegistrationPage(
            BrowserContext browserContext,
            String username,
            String displayName,
            String calendarName) {
        Page page = browserContext.newPage();
        page.navigate(registrationUrl());
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='displayName']").fill(displayName);
        page.locator("input[id$='calendarName']").fill(calendarName);
        page.locator("input[id$='password']").fill(VALID_PASSWORD);
        assertThat(page.locator("button:has-text('Register')")).isVisible();
        return page;
    }

    private void scheduleRegistration(Page page, long epochMilliseconds) {
        Locator registrationButton = page.locator("button:has-text('Register')");
        registrationButton.evaluate(
                "(element, submissionTime) => window.setTimeout("
                        + "() => element.click(), Math.max(0, Number(submissionTime) - Date.now()))",
                Long.toString(epochMilliseconds));
    }

    private void waitForRegistrationResult(Page page) {
        page.waitForFunction(
                "() => location.pathname === '/app/calendars' "
                        + "|| document.body.innerText.includes('Invitation is invalid or no longer available.')");
    }

    private boolean registrationSucceeded(Page page) {
        return URI.create(page.url()).getPath().equals("/app/calendars");
    }

    private void lockBootstrapRow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select singleton_id from app_registration_bootstrap "
                                + "where singleton_id = 1 for update");
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "The isolated bootstrap state row must exist.");
        }
    }

    private void waitForBlockedBootstrapClaims(int expectedBlockedClaims) throws Exception {
        long deadlineNanos = System.nanoTime() + BLOCKED_REQUEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            long blockedClaimCount = queryLong(
                    "select count(*) from pg_stat_activity "
                            + "where datname = current_database() "
                            + "and pid <> pg_backend_pid() "
                            + "and wait_event_type = 'Lock' "
                            + "and lower(query) like '%app_registration_bootstrap%'");
            if (blockedClaimCount >= expectedBlockedClaims) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        fail("Expected " + expectedBlockedClaims
                + " registration transactions to block on the bootstrap row within "
                + BLOCKED_REQUEST_TIMEOUT.toSeconds() + " seconds.");
    }

    private void assertFreshMigratedDatabase() throws SQLException {
        assertEquals(
                EXPECTED_LATEST_FLYWAY_VERSION,
                queryString("select version from flyway_schema_history "
                        + "where success = true order by installed_rank desc limit 1"));
        assertEquals(0L, queryLong("select count(*) from app_user"));
        assertFalse(queryBoolean(
                "select consumed_at is not null from app_registration_bootstrap where singleton_id = 1"));
        assertTrue(queryBoolean(
                "select pg_get_constraintdef(oid) like '%[AEIMQUYcgkosw048]%' "
                        + "from pg_constraint where conrelid = 'calendar'::regclass "
                        + "and conname = 'calendar_public_token_check'"));
        assertTrue(queryBoolean(
                "select convalidated and pg_get_constraintdef(oid) like '%7 days%' "
                        + "from pg_constraint where conrelid = 'app_invitation'::regclass "
                        + "and conname = 'app_invitation_maximum_lifetime_check'"));
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection connection = databaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "Expected a scalar database result for: " + sql);
            return resultSet.getLong(1);
        }
    }

    private boolean queryBoolean(String sql) throws SQLException {
        try (Connection connection = databaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "Expected a scalar database result for: " + sql);
            return resultSet.getBoolean(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection connection = databaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next(), "Expected a scalar database result for: " + sql);
            return resultSet.getString(1);
        }
    }

    private Connection databaseConnection() throws SQLException {
        String databasePort = environmentValueOrDefault(
                DATABASE_PORT_ENVIRONMENT_VARIABLE,
                DEFAULT_DATABASE_PORT);
        return DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + databasePort + "/" + DATABASE_NAME,
                DATABASE_USER,
                DATABASE_PASSWORD);
    }

    private void waitForApplicationHealth() throws InterruptedException {
        URI healthUri = applicationBaseUri.resolve("/health");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        long deadlineNanos = System.nanoTime() + APPLICATION_READY_TIMEOUT.toNanos();
        String lastHealthResult = "no response";

        while (System.nanoTime() < deadlineNanos) {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().trim().equals("ok")) {
                    return;
                }
                lastHealthResult = "HTTP " + response.statusCode();
            } catch (IOException exception) {
                lastHealthResult = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }
            Thread.sleep(Duration.ofMillis(250).toMillis());
        }
        fail("The isolated bootstrap application did not become healthy within "
                + APPLICATION_READY_TIMEOUT.toSeconds() + " seconds. Last result: " + lastHealthResult + ".");
    }

    private String registrationUrl() {
        return applicationBaseUri + "/register?token="
                + URLEncoder.encode(bootstrapInvitationToken, StandardCharsets.UTF_8);
    }

    private String environmentValueOrDefault(String variableName, String defaultValue) {
        String value = System.getenv(variableName);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String removeTrailingSlashes(String value) {
        String normalizedValue = value;
        while (normalizedValue.endsWith("/")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }
        return normalizedValue;
    }
}
