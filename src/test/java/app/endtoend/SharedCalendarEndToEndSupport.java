package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.SelectOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SharedCalendarEndToEndSupport {
    static final String TEST_PASSWORD = "correct horse battery staple";
    static final String BOOTSTRAP_INVITATION_TOKEN =
            "verification-only-bootstrap-token-0000000000";
    static final String SEEDED_PASSWORD_HASH =
            "PBKDF2WithHmacSHA256:600000:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=:"
                    + "YTpMNBE5TiT//mxRmUMHckVy5XS82Y6oz0V8ZImb+/4=";

    private static final AtomicLong CALENDAR_LINK_TOKEN_SEQUENCE = new AtomicLong(1);

    private URI applicationBaseUri;
    private String databaseHost;
    private String databasePort;
    private String databaseName;
    private String databaseUser;
    private String databasePassword;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void startBrowser() throws Exception {
        applicationBaseUri = requireIsolatedApplication();
        waitForHealth();
        verifyDatabaseTarget();

        playwright = Playwright.create();
        BrowserType browserType = switch (requiredEnvironment("BROWSER").toLowerCase(Locale.ROOT)) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> throw new IllegalStateException("BROWSER must be chromium, firefox, or webkit.");
        };
        browser = browserType.launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void stopBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    BrowserContext newBrowserContext() {
        return browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
    }

    String route(String path) {
        return applicationBaseUri.resolve(path).toString();
    }

    Response navigate(Page page, String path) {
        return page.navigate(route(path));
    }

    void navigateBearerLink(Page page, String link) {
        try {
            page.navigate(link);
        } catch (RuntimeException exception) {
            throw new AssertionError("The disposable invitation link could not be opened.");
        }
    }

    void signIn(Page page, String username, String password) {
        navigate(page, "/sign-in");
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='password']").fill(password);
        page.locator("input[type='submit'][value='Sign in']").click();
        page.waitForURL("**/app/calendars");
    }

    void fillRegistration(
            Page page,
            String username,
            String displayName,
            String calendarName,
            String password) {
        page.locator("input[id$='username']").fill(username);
        page.locator("input[id$='displayName']").fill(displayName);
        page.locator("input[id$='calendarName']").fill(calendarName);
        page.locator("input[id$='password']").fill(password);
        page.locator("input[id$='passwordConfirmation']").fill(password);
    }

    String createRegistrationInvitation(Page page) {
        navigate(page, "/app/invitations");
        page.locator("button:has-text('Generate registration link')").click();
        return page.locator("input[id$='generatedInvitationLink']").inputValue();
    }

    String createEditorInvitation(Page page, String calendarName) {
        navigate(page, "/app/invitations");
        page.locator("select[id$='calendar']")
                .selectOption(new SelectOption().setLabel(calendarName));
        page.locator("button:has-text('Generate editor link')").click();
        return page.locator("input[id$='generatedInvitationLink']").inputValue();
    }

    void createTimedEvent(
            Page page,
            String title,
            String location,
            String startTime,
            String endTime) {
        page.locator("input[id$='eventTitle']").fill(title);
        page.locator("input[id$='eventLocation']").fill(location);
        page.locator("input[id$='eventStart_input']").fill(startTime);
        page.locator("input[id$='eventEnd_input']").fill(endTime);
        Response createEventResponse = page.waitForResponse(
                response -> "POST".equals(response.request().method()),
                () -> page.locator("button:has-text('Create event')").click());
        assertTrue(
                createEventResponse.ok(),
                () -> "Create event POST returned "
                        + createEventResponse.status()
                        + " from "
                        + createEventResponse.url());
        assertThat(page.locator("body")).containsText("Event created.");
        assertThat(page.locator("article.event-item").filter(
                        new com.microsoft.playwright.Locator.FilterOptions().setHasText(title)))
                .isVisible();
    }

    void assertAccessible(Page page) {
        AxeResults results = new AxeBuilder(page).analyze();
        assertTrue(
                results.getViolations().isEmpty(),
                () -> "Accessibility violations: "
                        + results.getViolations().stream().map(violation -> violation.getId()).toList());
    }

    void assertResponsiveAndAccessible(Page page, int width, int height) {
        page.setViewportSize(width, height);
        assertFalse(
                Boolean.TRUE.equals(page.evaluate(
                        "() => document.documentElement.scrollWidth > document.documentElement.clientWidth")),
                () -> "Page has horizontal overflow at " + width + " by " + height + ".");
        assertAccessible(page);
    }

    void assertSecurityHeaders(Response response) {
        assertEquals("DENY", response.headerValue("x-frame-options"));
        assertEquals("nosniff", response.headerValue("x-content-type-options"));
        assertNotNull(response.headerValue("content-security-policy"));
    }

    long seedUser(String username, String displayName) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into app_user(username, display_name, password_hash) "
                                + "values (?, ?, ?) returning id")) {
            statement.setString(1, username);
            statement.setString(2, displayName);
            statement.setString(3, SEEDED_PASSWORD_HASH);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Seeded user did not return an identifier.");
                return resultSet.getLong(1);
            }
        }
    }

    SeededCalendar seedCalendar(long userId, String calendarName) throws SQLException {
        String calendarLinkToken = nextCalendarLinkToken();
        long calendarId;
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into calendar(name, calendar_link_token, time_zone, public_access_enabled) "
                                + "values (?, ?, 'Europe/Warsaw', true) returning id")) {
            statement.setString(1, calendarName);
            statement.setString(2, calendarLinkToken);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Seeded calendar did not return an identifier.");
                calendarId = resultSet.getLong(1);
            }
        }
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) "
                        + "values (?, ?, 'ADMIN')",
                calendarId,
                userId);
        return new SeededCalendar(calendarId, calendarLinkToken);
    }

    long queryLong(String query, Object... parameters) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            setParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Database query returned no row.");
                return resultSet.getLong(1);
            }
        }
    }

    String queryText(String query, Object... parameters) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            setParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Database query returned no row.");
                return resultSet.getString(1);
            }
        }
    }

    void executeUpdate(String statementText, Object... parameters) throws SQLException {
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(statementText)) {
            setParameters(statement, parameters);
            assertEquals(1, statement.executeUpdate(), "Database fixture update affected an unexpected row count.");
        }
    }

    Connection openDatabaseConnection() throws SQLException {
        String connectionUrl = "jdbc:postgresql://%s:%s/%s"
                .formatted(databaseHost, databasePort, databaseName);
        return DriverManager.getConnection(connectionUrl, databaseUser, databasePassword);
    }

    String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private URI requireIsolatedApplication() {
        String expectedBaseUrl = requiredEnvironment("END_TO_END_VERIFICATION_BASE_URL");
        String configuredBaseUrl = requiredEnvironment("APP_BASE_URL");
        assertEquals(
                expectedBaseUrl,
                configuredBaseUrl,
                "APP_BASE_URL must identify the isolated verification application.");

        URI baseUri = URI.create(expectedBaseUrl);
        if (!"https".equalsIgnoreCase(baseUri.getScheme())
                || !isLoopbackHost(baseUri.getHost())
                || baseUri.getRawQuery() != null
                || baseUri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "END_TO_END_VERIFICATION_BASE_URL must be a loopback HTTPS origin.");
        }

        databaseHost = requiredEnvironment("PGHOST");
        databasePort = requiredEnvironment("PGPORT");
        databaseName = requiredEnvironment("PGDATABASE");
        databaseUser = requiredEnvironment("PGUSER");
        databasePassword = requiredEnvironment("PGPASSWORD");
        if (!isLoopbackHost(databaseHost) || !databaseName.endsWith("_verification")) {
            throw new IllegalStateException(
                    "End-to-end tests require a loopback PostgreSQL database ending in _verification.");
        }
        return baseUri;
    }

    private void waitForHealth() throws Exception {
        URI healthUri = URI.create(requiredEnvironment("END_TO_END_VERIFICATION_HEALTH_URL"));
        if (!"http".equalsIgnoreCase(healthUri.getScheme()) || !isLoopbackHost(healthUri.getHost())) {
            throw new IllegalStateException(
                    "END_TO_END_VERIFICATION_HEALTH_URL must be a loopback HTTP URL.");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder(healthUri)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && "ok".equals(response.body())) {
                    return;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
                "The isolated application did not become healthy.",
                lastFailure);
    }

    private void verifyDatabaseTarget() throws SQLException {
        assertEquals(databaseName, queryText("select current_database()"));
        assertEquals(
                1,
                queryLong(
                        "select count(*) from flyway_schema_history "
                                + "where version = '1' and success = true"));
        assertEquals(1, queryLong("select count(*) from flyway_schema_history"));
    }

    private static void setParameters(PreparedStatement statement, Object[] parameters)
            throws SQLException {
        for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
            statement.setObject(parameterIndex + 1, parameters[parameterIndex]);
        }
    }

    private static String nextCalendarLinkToken() {
        byte[] tokenBytes = ByteBuffer.allocate(Long.BYTES)
                .putLong(CALENDAR_LINK_TOKEN_SEQUENCE.getAndIncrement())
                .array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value.trim();
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    record SeededCalendar(long id, String calendarLinkToken) {}
}
