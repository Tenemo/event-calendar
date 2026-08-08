package app.endtoend;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ApiTokenEndToEndIT extends SharedCalendarEndToEndSupport {
    private static final String API_TOKEN_PREFIX = "calendar_social_api_";
    private static final Pattern API_TOKEN_PATTERN =
            Pattern.compile(Pattern.quote(API_TOKEN_PREFIX) + "[A-Za-z0-9_-]{43}");
    private static final String UNKNOWN_API_TOKEN = API_TOKEN_PREFIX + "A".repeat(43);
    private static final Set<String> HTTP_OPERATION_NAMES =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace");
    private static final Set<String> EXPECTED_OPEN_API_OPERATIONS = Set.of(
            "GET /api/v1/me getCaller",
            "GET /api/v1/calendars listCalendars",
            "POST /api/v1/calendars createCalendar",
            "GET /api/v1/calendars/{calendarId} getCalendar",
            "PUT /api/v1/calendars/{calendarId} updateCalendar",
            "POST /api/v1/calendars/{calendarId}/calendar-link-regenerations regenerateCalendarLink",
            "GET /api/v1/calendars/{calendarId}/events listEvents",
            "POST /api/v1/calendars/{calendarId}/events createEvent",
            "GET /api/v1/calendars/{calendarId}/events/{eventId} getEvent",
            "PUT /api/v1/calendars/{calendarId}/events/{eventId} updateEvent",
            "DELETE /api/v1/calendars/{calendarId}/events/{eventId} deleteEvent",
            "GET /api/v1/calendars/{calendarId}/members listMembers",
            "PUT /api/v1/calendars/{calendarId}/members/{userId} changeMemberRole",
            "DELETE /api/v1/calendars/{calendarId}/members/{userId} removeMember",
            "POST /api/v1/registration-invitations createRegistrationInvitation",
            "POST /api/v1/calendars/{calendarId}/editor-invitations createEditorInvitation",
            "GET /api/v1/invitations listInvitations",
            "DELETE /api/v1/invitations/{invitationId} revokeInvitation",
            "POST /api/v1/invitation-acceptances acceptEditorInvitation");

    @Test
    void tokensAreIssuedOnceStoredAsDigestsAndRemainValidUntilExplicitlyRevoked() throws SQLException {
        String suffix = uniqueSuffix();
        String username = "token-lifetime-" + suffix;
        String displayName = "Token lifetime " + suffix;
        long userId = seedUser(username, displayName);

        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            signIn(page, username, TEST_PASSWORD);

            navigate(page, "/app/account-settings");
            assertThat(page.locator("body")).containsText("Tokens do not expire.");
            submitApiTokenName(page, "   ");
            assertThat(page.locator("body")).containsText("Token name is required.");
            assertEquals(0, queryLong("select count(*) from api_token where user_id = ?", userId));

            navigate(page, "/app/account-settings");
            Locator boundedTokenNameInput = page.locator("input[id$='tokenName']");
            assertEquals("80", boundedTokenNameInput.getAttribute("maxlength"));
            boundedTokenNameInput.fill("x".repeat(81));
            assertEquals(80, boundedTokenNameInput.inputValue().length());
            assertEquals(0, queryLong("select count(*) from api_token where user_id = ?", userId));

            String normalizedTokenName = "Laptop sync " + suffix;
            String plaintextToken = issueApiToken(page, "  " + normalizedTokenName + "  ");
            assertTrue(
                    API_TOKEN_PATTERN.matcher(plaintextToken).matches(),
                    "The issued token should use the documented opaque format.");

            String storedDigest = queryText(
                    "select token_digest from api_token where user_id = ? and name = ?",
                    userId,
                    normalizedTokenName);
            assertEquals(64, storedDigest.length());
            assertFalse(storedDigest.contains(plaintextToken));
            assertEquals(
                    plaintextToken.substring(plaintextToken.length() - 8),
                    queryText(
                            "select token_hint from api_token where user_id = ? and name = ?",
                            userId,
                            normalizedTokenName));
            assertNull(queryText(
                    "select last_used_at::text from api_token where user_id = ? and name = ?",
                    userId,
                    normalizedTokenName));
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from api_token "
                                    + "where user_id = ? and name = ? and created_at is not null",
                            userId,
                            normalizedTokenName));
            assertEquals(
                    0,
                    queryLong("select count(*) from api_token where token_digest = ?", plaintextToken));
            assertEquals(
                    0,
                    queryLong(
                            "select count(*) from information_schema.columns "
                                    + "where table_schema = 'public' and table_name = 'api_token' "
                                    + "and column_name in ('expires_at', 'scope', 'scopes')"));

            Locator tokenCard = apiTokenCard(page, normalizedTokenName);
            assertThat(tokenCard).containsText("Last used Never");
            assertThat(tokenCard).containsText("Ending in " + plaintextToken.substring(plaintextToken.length() - 8));

            navigate(page, "/app/account-settings");
            assertFalse(
                    page.content().contains(plaintextToken),
                    "The plaintext token must disappear after its one-time response.");

            String changedPassword = "new correct horse battery staple " + suffix;
            page.locator("input[id$='currentPassword']").fill(TEST_PASSWORD);
            page.locator("input[id$='newPassword']").fill(changedPassword);
            page.locator("input[id$='newPasswordConfirmation']").fill(changedPassword);
            page.locator("button:has-text('Change password')").click();
            page.waitForURL("**/sign-in**");
            assertThat(page.locator("body")).containsText("Your password was changed.");

            ApiResponse callerResponse = apiRequest(
                    page, "/api/v1/me", "GET", plaintextToken, null, null);
            assertJsonStatus(callerResponse, 200, "/api/v1/me");
            assertEquals(username, responseObject(callerResponse).get("username"));
            assertEquals(displayName, responseObject(callerResponse).get("displayName"));
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from api_token "
                                    + "where user_id = ? and name = ? and last_used_at is not null",
                            userId,
                            normalizedTokenName));

            signIn(page, username, changedPassword);
            navigate(page, "/app/account-settings");
            assertFalse(
                    apiTokenCard(page, normalizedTokenName).innerText().contains("Last used Never"),
                    "Account settings should show that the token has been used.");
        }
    }

    @Test
    void bearerAuthenticationRejectsMissingMalformedUnknownAndNonApiCredentials() throws SQLException {
        String suffix = uniqueSuffix();
        String username = "token-authentication-" + suffix;
        String displayName = "Token authentication " + suffix;
        long userId = seedUser(username, displayName);
        SeededCalendar calendar = seedCalendar(userId, "Authentication calendar " + suffix);
        String tokenName = "Authentication token " + suffix;
        String token = seedApiToken(userId, tokenName);

        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            signIn(page, username, TEST_PASSWORD);

            ApiResponse validResponse = apiRequest(page, "/api/v1/me", "GET", token, null, null);
            assertJsonStatus(validResponse, 200, "/api/v1/me");
            assertEquals(userId, number(responseObject(validResponse).get("id")));
            assertEquals(username, responseObject(validResponse).get("username"));

            assertUnauthorized(
                    apiRequest(page, "/api/v1/me", "GET", null, null, null),
                    "/api/v1/me");
            assertUnauthorized(cookieOnlyApiRequest(page, "/api/v1/me"), "/api/v1/me");
            assertUnauthorized(
                    apiRequest(page, "/api/v1/me", "GET", UNKNOWN_API_TOKEN, null, null),
                    "/api/v1/me");
            assertUnauthorized(
                    apiRequest(
                            page,
                            "/api/v1/me",
                            "GET",
                            calendar.calendarLinkToken(),
                            null,
                            null),
                    "/api/v1/me");
            assertUnauthorized(
                    apiRequestWithHeaders(
                            page,
                            "/api/v1/me",
                            "GET",
                            Map.of("Accept", "application/json", "Authorization", "Basic dXNlcjpwYXNz"),
                            null,
                            false),
                    "/api/v1/me");
            assertUnauthorized(
                    apiRequestWithHeaders(
                            page,
                            "/api/v1/me",
                            "GET",
                            Map.of("Accept", "application/json", "Authorization", "Bearer invalid!"),
                            null,
                            false),
                    "/api/v1/me");

            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from api_token "
                                    + "where user_id = ? and name = ? and last_used_at is not null",
                            userId,
                            tokenName));
            navigate(page, "/app/account-settings");
            assertFalse(
                    apiTokenCard(page, tokenName).innerText().contains("Last used Never"),
                    "A successful bearer request should update the visible last-used timestamp.");
        }
    }

    @Test
    void revocationIsImmediateSelectiveAndBoundToTheTokenOwner() throws SQLException {
        String suffix = uniqueSuffix();
        String ownerUsername = "token-owner-" + suffix;
        String foreignUsername = "token-foreign-" + suffix;
        long ownerId = seedUser(ownerUsername, "Token owner " + suffix);
        long foreignUserId = seedUser(foreignUsername, "Foreign token owner " + suffix);
        String firstTokenName = "First device " + suffix;
        String secondTokenName = "Second device " + suffix;
        String foreignTokenName = "Foreign device " + suffix;
        String firstToken = seedApiToken(ownerId, firstTokenName);
        String secondToken = seedApiToken(ownerId, secondTokenName);
        String foreignToken = seedApiToken(foreignUserId, foreignTokenName);

        try (BrowserContext ownerContext = newBrowserContext()) {
            Page ownerPage = ownerContext.newPage();
            signIn(ownerPage, ownerUsername, TEST_PASSWORD);

            long firstTokenId = queryLong(
                    "select id from api_token where user_id = ? and name = ?", ownerId, firstTokenName);
            long foreignTokenId = queryLong(
                    "select id from api_token where user_id = ? and name = ?",
                    foreignUserId,
                    foreignTokenName);

            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", firstToken, null, null).status());
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", secondToken, null, null).status());
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", foreignToken, null, null).status());

            navigate(ownerPage, "/app/account-settings");
            assertEquals(1, apiTokenCard(ownerPage, firstTokenName).count());
            assertEquals(1, apiTokenCard(ownerPage, secondTokenName).count());
            assertEquals(0, apiTokenCard(ownerPage, foreignTokenName).count());

            submitRevocationWithTokenId(ownerPage, firstTokenName, foreignTokenId);
            assertThat(ownerPage.locator("body")).containsText("Revoke failed.");
            assertThat(ownerPage.locator("body")).containsText("API token was not found.");
            assertEquals(1, queryLong("select count(*) from api_token where id = ?", foreignTokenId));
            assertEquals(1, queryLong("select count(*) from api_token where id = ?", firstTokenId));
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", firstToken, null, null).status());
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", foreignToken, null, null).status());

            revokeApiToken(ownerPage, firstTokenName);
            assertThat(ownerPage.locator("body")).containsText("API token revoked.");
            assertEquals(0, queryLong("select count(*) from api_token where id = ?", firstTokenId));
            assertUnauthorized(
                    apiRequest(ownerPage, "/api/v1/me", "GET", firstToken, null, null),
                    "/api/v1/me");
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", secondToken, null, null).status());
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", foreignToken, null, null).status());

            submitRevocationWithTokenId(ownerPage, secondTokenName, firstTokenId);
            assertThat(ownerPage.locator("body")).containsText("API token was not found.");
            assertEquals(200, apiRequest(ownerPage, "/api/v1/me", "GET", secondToken, null, null).status());
            assertEquals(1, queryLong("select count(*) from api_token where user_id = ?", ownerId));
            assertEquals(1, queryLong("select count(*) from api_token where user_id = ?", foreignUserId));
        }
    }

    @Test
    void calendarOperationsEnforceLiveRolesValidationAndLinkRegeneration() throws SQLException {
        String suffix = uniqueSuffix();
        String adminUsername = "calendar-admin-" + suffix;
        String editorUsername = "calendar-editor-" + suffix;
        String outsiderUsername = "calendar-outsider-" + suffix;
        long adminId = seedUser(adminUsername, "Calendar admin " + suffix);
        long editorId = seedUser(editorUsername, "Calendar editor " + suffix);
        long outsiderId = seedUser(outsiderUsername, "Calendar outsider " + suffix);
        String calendarName = "API role calendar " + suffix;
        SeededCalendar calendar = seedCalendar(adminId, calendarName);
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) values (?, ?, 'EDITOR')",
                calendar.id(),
                editorId);
        String adminToken = seedApiToken(adminId, "Calendar admin token " + suffix);
        String editorToken = seedApiToken(editorId, "Calendar editor token " + suffix);
        String outsiderToken = seedApiToken(outsiderId, "Calendar outsider token " + suffix);

        try (BrowserContext adminContext = newBrowserContext();
                BrowserContext editorContext = newBrowserContext();
                BrowserContext outsiderContext = newBrowserContext();
                BrowserContext anonymousContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            Page editorPage = editorContext.newPage();
            Page outsiderPage = outsiderContext.newPage();
            Page anonymousPage = anonymousContext.newPage();
            navigate(adminPage, "/sign-in");
            navigate(editorPage, "/sign-in");
            navigate(outsiderPage, "/sign-in");

            Map<String, Object> adminSummary = findObjectById(
                    responseList(apiRequest(
                            adminPage, "/api/v1/calendars", "GET", adminToken, null, null)),
                    calendar.id());
            Map<String, Object> editorSummary = findObjectById(
                    responseList(apiRequest(
                            adminPage, "/api/v1/calendars", "GET", editorToken, null, null)),
                    calendar.id());
            assertEquals("ADMIN", adminSummary.get("role"));
            assertEquals("EDITOR", editorSummary.get("role"));
            assertFalse(responseList(apiRequest(
                            adminPage, "/api/v1/calendars", "GET", outsiderToken, null, null))
                    .stream()
                    .anyMatch(item -> number(item.get("id")) == calendar.id()));

            String createdCalendarName = "Created through API " + suffix;
            ApiResponse createdCalendarResponse = apiRequest(
                    adminPage,
                    "/api/v1/calendars",
                    "POST",
                    outsiderToken,
                    null,
                    "{\"name\":\"" + createdCalendarName + "\"}");
            assertJsonStatus(createdCalendarResponse, 201, "/api/v1/calendars");
            Map<String, Object> createdCalendar = responseObject(createdCalendarResponse);
            long createdCalendarId = number(createdCalendar.get("id"));
            assertEquals("ADMIN", createdCalendar.get("role"));
            assertEquals("Europe/Warsaw", createdCalendar.get("timeZone"));
            assertEquals(Boolean.TRUE, createdCalendar.get("publicAccessEnabled"));
            assertNotNull(createdCalendarResponse.entityTag());
            assertTrue(createdCalendarResponse.location().endsWith("/api/v1/calendars/" + createdCalendarId));
            assertEquals(
                    "ADMIN",
                    queryText(
                            "select role_name from calendar_membership where calendar_id = ? and user_id = ?",
                            createdCalendarId,
                            outsiderId));

            String calendarPath = "/api/v1/calendars/" + calendar.id();
            ApiResponse adminCalendarResponse =
                    apiRequest(adminPage, calendarPath, "GET", adminToken, null, null);
            ApiResponse editorCalendarResponse =
                    apiRequest(adminPage, calendarPath, "GET", editorToken, null, null);
            assertJsonStatus(adminCalendarResponse, 200, calendarPath);
            assertJsonStatus(editorCalendarResponse, 200, calendarPath);
            assertEquals("ADMIN", responseObject(adminCalendarResponse).get("role"));
            assertEquals("EDITOR", responseObject(editorCalendarResponse).get("role"));
            assertProblem(
                    apiRequest(adminPage, calendarPath, "GET", outsiderToken, null, null),
                    403,
                    "Forbidden",
                    calendarPath);

            String currentEntityTag = adminCalendarResponse.entityTag();
            String validSettings = calendarSettingsInput(
                    calendarName, "Managed through the API", "Europe/Warsaw", true);
            assertProblem(
                    apiRequest(editorPage, calendarPath, "PUT", editorToken, currentEntityTag, validSettings),
                    403,
                    "Forbidden",
                    calendarPath);
            assertProblem(
                    apiRequest(outsiderPage, calendarPath, "PUT", outsiderToken, currentEntityTag, validSettings),
                    403,
                    "Forbidden",
                    calendarPath);
            assertProblem(
                    apiRequest(adminPage, calendarPath, "PUT", adminToken, null, validSettings),
                    428,
                    "Precondition required",
                    calendarPath);
            assertProblem(
                    apiRequest(adminPage, calendarPath, "PUT", adminToken, "\"999999\"", validSettings),
                    412,
                    "Precondition failed",
                    calendarPath);
            assertProblem(
                    apiRequest(
                            adminPage,
                            calendarPath,
                            "PUT",
                            adminToken,
                            currentEntityTag,
                            calendarSettingsInput(calendarName, null, "Mars/Olympus", true)),
                    422,
                    "Validation failed",
                    calendarPath);
            assertNull(queryText("select description from calendar where id = ?", calendar.id()));

            ApiResponse updatedCalendarResponse = apiRequest(
                    adminPage, calendarPath, "PUT", adminToken, currentEntityTag, validSettings);
            assertJsonStatus(updatedCalendarResponse, 200, calendarPath);
            assertEquals("Managed through the API", responseObject(updatedCalendarResponse).get("description"));
            assertNotEquals(currentEntityTag, updatedCalendarResponse.entityTag());
            assertEquals(
                    "Managed through the API",
                    queryText("select description from calendar where id = ?", calendar.id()));

            String originalPublicUrl = String.valueOf(responseObject(updatedCalendarResponse).get("publicUrl"));
            Response originalPublicResponse = anonymousPage.navigate(originalPublicUrl);
            assertEquals(200, originalPublicResponse.status());

            String regenerationPath = calendarPath + "/calendar-link-regenerations";
            assertProblem(
                    apiRequest(
                            editorPage,
                            regenerationPath,
                            "POST",
                            editorToken,
                            updatedCalendarResponse.entityTag(),
                            null),
                    403,
                    "Forbidden",
                    regenerationPath);
            assertProblem(
                    apiRequest(
                            outsiderPage,
                            regenerationPath,
                            "POST",
                            outsiderToken,
                            updatedCalendarResponse.entityTag(),
                            null),
                    403,
                    "Forbidden",
                    regenerationPath);

            ApiResponse regeneratedCalendarResponse = apiRequest(
                    adminPage,
                    regenerationPath,
                    "POST",
                    adminToken,
                    updatedCalendarResponse.entityTag(),
                    null);
            assertJsonStatus(regeneratedCalendarResponse, 200, regenerationPath);
            String regeneratedPublicUrl = String.valueOf(responseObject(regeneratedCalendarResponse).get("publicUrl"));
            assertNotEquals(originalPublicUrl, regeneratedPublicUrl);
            assertNotEquals(updatedCalendarResponse.entityTag(), regeneratedCalendarResponse.entityTag());
            assertEquals(404, anonymousPage.navigate(originalPublicUrl).status());
            assertEquals(200, anonymousPage.navigate(regeneratedPublicUrl).status());
            assertEquals(
                    URI.create(regeneratedPublicUrl).getPath().substring(1),
                    queryText("select calendar_link_token from calendar where id = ?", calendar.id()));

            ApiResponse createdCalendarRead = apiRequest(
                    outsiderPage,
                    "/api/v1/calendars/" + createdCalendarId,
                    "GET",
                    outsiderToken,
                    null,
                    null);
            assertEquals("ADMIN", responseObject(createdCalendarRead).get("role"));
        }
    }

    @Test
    void eventOperationsCoverEveryRouteContainmentPreconditionsAndCivilDates() throws SQLException {
        String suffix = uniqueSuffix();
        String adminUsername = "event-admin-" + suffix;
        String editorUsername = "event-editor-" + suffix;
        String outsiderUsername = "event-outsider-" + suffix;
        long adminId = seedUser(adminUsername, "Event admin " + suffix);
        long editorId = seedUser(editorUsername, "Event editor " + suffix);
        long outsiderId = seedUser(outsiderUsername, "Event outsider " + suffix);
        String calendarName = "Event API calendar " + suffix;
        SeededCalendar calendar = seedCalendar(adminId, calendarName);
        SeededCalendar otherCalendar = seedCalendar(adminId, "Other event API calendar " + suffix);
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) values (?, ?, 'EDITOR')",
                calendar.id(),
                editorId);
        String adminToken = seedApiToken(adminId, "Event admin token " + suffix);
        String editorToken = seedApiToken(editorId, "Event editor token " + suffix);
        String outsiderToken = seedApiToken(outsiderId, "Event outsider token " + suffix);

        try (BrowserContext adminContext = newBrowserContext();
                BrowserContext editorContext = newBrowserContext();
                BrowserContext outsiderContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            Page editorPage = editorContext.newPage();
            Page outsiderPage = outsiderContext.newPage();
            navigate(adminPage, "/sign-in");
            navigate(editorPage, "/sign-in");
            navigate(outsiderPage, "/sign-in");

            String calendarPath = "/api/v1/calendars/" + calendar.id();
            String eventsPath = calendarPath + "/events";
            ApiResponse calendarResponse =
                    apiRequest(editorPage, calendarPath, "GET", editorToken, null, null);
            String allDayTitle = "Inclusive kayaking days " + suffix;
            String allDayInput = allDayEventInput(
                    allDayTitle,
                    "Civil dates must survive time-zone changes",
                    "Lake",
                    "2026-08-10",
                    "2026-08-12");

            assertProblem(
                    apiRequest(editorPage, eventsPath, "POST", editorToken, null, allDayInput),
                    428,
                    "Precondition required",
                    eventsPath);
            assertEquals(0, queryLong("select count(*) from calendar_event where calendar_id = ?", calendar.id()));

            ApiResponse createdAllDayResponse = apiRequest(
                    editorPage,
                    eventsPath,
                    "POST",
                    editorToken,
                    calendarResponse.entityTag(),
                    allDayInput);
            assertJsonStatus(createdAllDayResponse, 201, eventsPath);
            Map<String, Object> createdAllDayEvent = responseObject(createdAllDayResponse);
            long allDayEventId = number(createdAllDayEvent.get("id"));
            assertTrue(createdAllDayResponse.location().endsWith(eventsPath + "/" + allDayEventId));
            assertEquals("all-day", nestedObject(createdAllDayEvent, "time").get("kind"));
            assertEquals("2026-08-10", nestedObject(createdAllDayEvent, "time").get("firstDay"));
            assertEquals("2026-08-12", nestedObject(createdAllDayEvent, "time").get("lastDay"));

            ApiResponse refreshedCalendarResponse =
                    apiRequest(editorPage, calendarPath, "GET", editorToken, null, null);
            String timedTitle = "Early paddle " + suffix;
            String timedInput = timedEventInput(
                    timedTitle,
                    null,
                    "River",
                    "2026-08-09T08:30:00",
                    "2026-08-09T10:00:00");
            ApiResponse createdTimedResponse = apiRequest(
                    editorPage,
                    eventsPath,
                    "POST",
                    editorToken,
                    refreshedCalendarResponse.entityTag(),
                    timedInput);
            assertJsonStatus(createdTimedResponse, 201, eventsPath);
            long timedEventId = number(responseObject(createdTimedResponse).get("id"));
            assertEquals("timed", nestedObject(responseObject(createdTimedResponse), "time").get("kind"));

            ApiResponse editorListResponse =
                    apiRequest(editorPage, eventsPath, "GET", editorToken, null, null);
            ApiResponse adminListResponse =
                    apiRequest(adminPage, eventsPath, "GET", adminToken, null, null);
            assertJsonStatus(editorListResponse, 200, eventsPath);
            assertJsonStatus(adminListResponse, 200, eventsPath);
            List<Map<String, Object>> listedEvents = responseList(editorListResponse);
            assertEquals(List.of(timedEventId, allDayEventId), listedEvents.stream()
                    .map(event -> number(event.get("id")))
                    .toList());
            assertEquals(2, responseList(adminListResponse).size());
            assertProblem(
                    apiRequest(outsiderPage, eventsPath, "GET", outsiderToken, null, null),
                    403,
                    "Forbidden",
                    eventsPath);

            String allDayEventPath = eventsPath + "/" + allDayEventId;
            ApiResponse allDayReadResponse =
                    apiRequest(editorPage, allDayEventPath, "GET", editorToken, null, null);
            assertJsonStatus(allDayReadResponse, 200, allDayEventPath);
            assertEquals(allDayTitle, responseObject(allDayReadResponse).get("title"));
            assertNotNull(allDayReadResponse.entityTag());
            assertProblem(
                    apiRequest(outsiderPage, allDayEventPath, "GET", outsiderToken, null, null),
                    404,
                    "Not found",
                    allDayEventPath);
            assertProblem(
                    apiRequest(
                            outsiderPage,
                            allDayEventPath,
                            "PUT",
                            outsiderToken,
                            allDayReadResponse.entityTag(),
                            allDayInput),
                    403,
                    "Forbidden",
                    allDayEventPath);
            assertProblem(
                    apiRequest(
                            outsiderPage,
                            allDayEventPath,
                            "DELETE",
                            outsiderToken,
                            allDayReadResponse.entityTag(),
                            null),
                    403,
                    "Forbidden",
                    allDayEventPath);

            String wrongCalendarEventPath = "/api/v1/calendars/"
                    + otherCalendar.id()
                    + "/events/"
                    + allDayEventId;
            assertProblem(
                    apiRequest(adminPage, wrongCalendarEventPath, "GET", adminToken, null, null),
                    404,
                    "Not found",
                    wrongCalendarEventPath);
            assertProblem(
                    apiRequest(
                            adminPage,
                            wrongCalendarEventPath,
                            "PUT",
                            adminToken,
                            allDayReadResponse.entityTag(),
                            allDayInput),
                    404,
                    "Not found",
                    wrongCalendarEventPath);
            assertProblem(
                    apiRequest(
                            adminPage,
                            wrongCalendarEventPath,
                            "DELETE",
                            adminToken,
                            allDayReadResponse.entityTag(),
                            null),
                    404,
                    "Not found",
                    wrongCalendarEventPath);
            assertEquals(allDayTitle, queryText("select title from calendar_event where id = ?", allDayEventId));

            assertProblem(
                    apiRequest(editorPage, allDayEventPath, "PUT", editorToken, null, allDayInput),
                    428,
                    "Precondition required",
                    allDayEventPath);
            String invalidTimeInput = timedEventInput(
                    allDayTitle,
                    null,
                    null,
                    "2026-08-10T20:00:00",
                    "2026-08-10T18:00:00");
            assertProblem(
                    apiRequest(
                            editorPage,
                            allDayEventPath,
                            "PUT",
                            editorToken,
                            allDayReadResponse.entityTag(),
                            invalidTimeInput),
                    422,
                    "Validation failed",
                    allDayEventPath);
            assertEquals(allDayTitle, queryText("select title from calendar_event where id = ?", allDayEventId));

            String updatedAllDayTitle = allDayTitle + " updated";
            String updatedAllDayInput = allDayEventInput(
                    updatedAllDayTitle,
                    null,
                    "New lake",
                    "2026-08-10",
                    "2026-08-12");
            ApiResponse updatedAllDayResponse = apiRequest(
                    editorPage,
                    allDayEventPath,
                    "PUT",
                    editorToken,
                    allDayReadResponse.entityTag(),
                    updatedAllDayInput);
            assertJsonStatus(updatedAllDayResponse, 200, allDayEventPath);
            assertNotEquals(allDayReadResponse.entityTag(), updatedAllDayResponse.entityTag());
            assertProblem(
                    apiRequest(
                            editorPage,
                            allDayEventPath,
                            "PUT",
                            editorToken,
                            allDayReadResponse.entityTag(),
                            updatedAllDayInput),
                    412,
                    "Precondition failed",
                    allDayEventPath);

            ApiResponse calendarBeforeTimeZoneChange =
                    apiRequest(adminPage, calendarPath, "GET", adminToken, null, null);
            ApiResponse changedTimeZoneResponse = apiRequest(
                    adminPage,
                    calendarPath,
                    "PUT",
                    adminToken,
                    calendarBeforeTimeZoneChange.entityTag(),
                    calendarSettingsInput(calendarName, null, "America/Los_Angeles", true));
            assertJsonStatus(changedTimeZoneResponse, 200, calendarPath);
            ApiResponse allDayAfterTimeZoneChange =
                    apiRequest(editorPage, allDayEventPath, "GET", editorToken, null, null);
            Map<String, Object> preservedAllDayTime =
                    nestedObject(responseObject(allDayAfterTimeZoneChange), "time");
            assertEquals("2026-08-10", preservedAllDayTime.get("firstDay"));
            assertEquals("2026-08-12", preservedAllDayTime.get("lastDay"));

            String timedEventPath = eventsPath + "/" + timedEventId;
            ApiResponse timedReadResponse =
                    apiRequest(editorPage, timedEventPath, "GET", editorToken, null, null);
            String updatedTimedInput = timedEventInput(
                    timedTitle + " updated",
                    "Bring paddles",
                    "River launch",
                    "2026-08-09T09:00:00",
                    "2026-08-09T11:00:00");
            ApiResponse updatedTimedResponse = apiRequest(
                    editorPage,
                    timedEventPath,
                    "PUT",
                    editorToken,
                    timedReadResponse.entityTag(),
                    updatedTimedInput);
            assertJsonStatus(updatedTimedResponse, 200, timedEventPath);
            assertEquals("timed", nestedObject(responseObject(updatedTimedResponse), "time").get("kind"));

            assertProblem(
                    apiRequest(editorPage, timedEventPath, "DELETE", editorToken, null, null),
                    428,
                    "Precondition required",
                    timedEventPath);
            ApiResponse deletedTimedResponse = apiRequest(
                    editorPage,
                    timedEventPath,
                    "DELETE",
                    editorToken,
                    updatedTimedResponse.entityTag(),
                    null);
            assertEquals(204, deletedTimedResponse.status());
            assertEquals("", deletedTimedResponse.rawBody());
            assertProblem(
                    apiRequest(editorPage, timedEventPath, "GET", editorToken, null, null),
                    404,
                    "Not found",
                    timedEventPath);
            assertEquals(0, queryLong("select count(*) from calendar_event where id = ?", timedEventId));
            assertEquals(1, queryLong("select count(*) from calendar_event where id = ?", allDayEventId));
        }
    }

    @Test
    void membershipAdministrationChangesExistingTokenPermissionsImmediately() throws SQLException {
        String suffix = uniqueSuffix();
        String adminUsername = "member-admin-" + suffix;
        String editorUsername = "member-editor-" + suffix;
        String outsiderUsername = "member-outsider-" + suffix;
        long adminId = seedUser(adminUsername, "Membership admin " + suffix);
        long editorId = seedUser(editorUsername, "Membership editor " + suffix);
        long outsiderId = seedUser(outsiderUsername, "Membership outsider " + suffix);
        SeededCalendar calendar = seedCalendar(adminId, "Membership API calendar " + suffix);
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) values (?, ?, 'EDITOR')",
                calendar.id(),
                editorId);
        String adminToken = seedApiToken(adminId, "Membership admin token " + suffix);
        String editorToken = seedApiToken(editorId, "Membership editor token " + suffix);
        String outsiderToken = seedApiToken(outsiderId, "Membership outsider token " + suffix);

        try (BrowserContext adminContext = newBrowserContext();
                BrowserContext editorContext = newBrowserContext();
                BrowserContext outsiderContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            Page editorPage = editorContext.newPage();
            Page outsiderPage = outsiderContext.newPage();
            navigate(adminPage, "/sign-in");
            navigate(editorPage, "/sign-in");
            navigate(outsiderPage, "/sign-in");

            String calendarPath = "/api/v1/calendars/" + calendar.id();
            String membersPath = calendarPath + "/members";
            String adminMembershipPath = membersPath + "/" + adminId;
            String editorMembershipPath = membersPath + "/" + editorId;

            ApiResponse adminMembersResponse =
                    apiRequest(adminPage, membersPath, "GET", adminToken, null, null);
            assertJsonStatus(adminMembersResponse, 200, membersPath);
            List<Map<String, Object>> members = responseList(adminMembersResponse);
            assertEquals(2, members.size());
            assertEquals("ADMIN", findMembershipByUserId(members, adminId).get("role"));
            assertEquals("EDITOR", findMembershipByUserId(members, editorId).get("role"));
            assertProblem(
                    apiRequest(editorPage, membersPath, "GET", editorToken, null, null),
                    403,
                    "Forbidden",
                    membersPath);
            assertProblem(
                    apiRequest(outsiderPage, membersPath, "GET", outsiderToken, null, null),
                    403,
                    "Forbidden",
                    membersPath);

            assertProblem(
                    apiRequest(
                            editorPage,
                            editorMembershipPath,
                            "PUT",
                            editorToken,
                            null,
                            "{\"role\":\"ADMIN\"}"),
                    403,
                    "Forbidden",
                    editorMembershipPath);
            assertProblem(
                    apiRequest(editorPage, editorMembershipPath, "DELETE", editorToken, null, null),
                    403,
                    "Forbidden",
                    editorMembershipPath);
            assertEquals(
                    "EDITOR",
                    queryText(
                            "select role_name from calendar_membership where calendar_id = ? and user_id = ?",
                            calendar.id(),
                            editorId));

            assertProblem(
                    apiRequest(
                            adminPage,
                            adminMembershipPath,
                            "PUT",
                            adminToken,
                            null,
                            "{\"role\":\"EDITOR\"}"),
                    422,
                    "Validation failed",
                    adminMembershipPath);
            assertProblem(
                    apiRequest(adminPage, adminMembershipPath, "DELETE", adminToken, null, null),
                    422,
                    "Validation failed",
                    adminMembershipPath);
            assertEquals(
                    "ADMIN",
                    queryText(
                            "select role_name from calendar_membership where calendar_id = ? and user_id = ?",
                            calendar.id(),
                            adminId));

            String missingMembershipPath = membersPath + "/999999999";
            assertProblem(
                    apiRequest(
                            adminPage,
                            missingMembershipPath,
                            "PUT",
                            adminToken,
                            null,
                            "{\"role\":\"EDITOR\"}"),
                    404,
                    "Not found",
                    missingMembershipPath);

            ApiResponse promotedEditorResponse = apiRequest(
                    adminPage,
                    editorMembershipPath,
                    "PUT",
                    adminToken,
                    null,
                    "{\"role\":\"ADMIN\"}");
            assertJsonStatus(promotedEditorResponse, 200, editorMembershipPath);
            assertEquals("ADMIN", responseObject(promotedEditorResponse).get("role"));
            assertEquals(
                    200,
                    apiRequest(editorPage, membersPath, "GET", editorToken, null, null).status(),
                    "The existing editor token should gain admin access immediately after promotion.");

            ApiResponse demotedEditorResponse = apiRequest(
                    adminPage,
                    editorMembershipPath,
                    "PUT",
                    adminToken,
                    null,
                    "{\"role\":\"EDITOR\"}");
            assertJsonStatus(demotedEditorResponse, 200, editorMembershipPath);
            assertEquals("EDITOR", responseObject(demotedEditorResponse).get("role"));
            assertProblem(
                    apiRequest(editorPage, membersPath, "GET", editorToken, null, null),
                    403,
                    "Forbidden",
                    membersPath);

            ApiResponse removedEditorResponse = apiRequest(
                    adminPage, editorMembershipPath, "DELETE", adminToken, null, null);
            assertEquals(204, removedEditorResponse.status());
            assertEquals(0, queryLong(
                    "select count(*) from calendar_membership where calendar_id = ? and user_id = ?",
                    calendar.id(),
                    editorId));

            ApiResponse editorCallerResponse =
                    apiRequest(editorPage, "/api/v1/me", "GET", editorToken, null, null);
            assertJsonStatus(editorCallerResponse, 200, "/api/v1/me");
            assertEquals(editorUsername, responseObject(editorCallerResponse).get("username"));
            assertFalse(responseList(apiRequest(
                            editorPage, "/api/v1/calendars", "GET", editorToken, null, null))
                    .stream()
                    .anyMatch(item -> number(item.get("id")) == calendar.id()));
            assertProblem(
                    apiRequest(editorPage, calendarPath, "GET", editorToken, null, null),
                    403,
                    "Forbidden",
                    calendarPath);
            assertProblem(
                    apiRequest(editorPage, calendarPath + "/events", "GET", editorToken, null, null),
                    403,
                    "Forbidden",
                    calendarPath + "/events");
            assertEquals(0, queryLong(
                    "select count(*) from calendar_membership where calendar_id = ? and user_id = ?",
                    calendar.id(),
                    outsiderId));
        }
    }

    @Test
    void invitationOperationsEnforceVisibilityAuthorityExpiryAndConcurrentSingleUse() throws SQLException {
        String suffix = uniqueSuffix();
        String adminUsername = "invite-admin-" + suffix;
        String editorUsername = "invite-editor-" + suffix;
        String outsiderUsername = "invite-outsider-" + suffix;
        String firstAcceptorUsername = "invite-first-" + suffix;
        String secondAcceptorUsername = "invite-second-" + suffix;
        long adminId = seedUser(adminUsername, "Invitation admin " + suffix);
        long editorId = seedUser(editorUsername, "Invitation editor " + suffix);
        long outsiderId = seedUser(outsiderUsername, "Invitation outsider " + suffix);
        long firstAcceptorId = seedUser(firstAcceptorUsername, "First invitation acceptor " + suffix);
        long secondAcceptorId = seedUser(secondAcceptorUsername, "Second invitation acceptor " + suffix);
        SeededCalendar calendar = seedCalendar(adminId, "Invitation API calendar " + suffix);
        executeUpdate(
                "insert into calendar_membership(calendar_id, user_id, role_name) values (?, ?, 'EDITOR')",
                calendar.id(),
                editorId);
        String adminToken = seedApiToken(adminId, "Invitation admin token " + suffix);
        String editorToken = seedApiToken(editorId, "Invitation editor token " + suffix);
        String outsiderToken = seedApiToken(outsiderId, "Invitation outsider token " + suffix);
        String firstAcceptorToken = seedApiToken(firstAcceptorId, "First acceptor token " + suffix);
        String secondAcceptorToken = seedApiToken(secondAcceptorId, "Second acceptor token " + suffix);

        try (BrowserContext adminContext = newBrowserContext();
                BrowserContext editorContext = newBrowserContext();
                BrowserContext outsiderContext = newBrowserContext();
                BrowserContext firstAcceptorContext = newBrowserContext();
                BrowserContext secondAcceptorContext = newBrowserContext()) {
            Page adminPage = adminContext.newPage();
            Page editorPage = editorContext.newPage();
            Page outsiderPage = outsiderContext.newPage();
            Page firstAcceptorPage = firstAcceptorContext.newPage();
            Page secondAcceptorPage = secondAcceptorContext.newPage();
            navigate(adminPage, "/sign-in");
            navigate(editorPage, "/sign-in");
            navigate(outsiderPage, "/sign-in");
            navigate(firstAcceptorPage, "/sign-in");
            navigate(secondAcceptorPage, "/sign-in");

            String registrationInvitationsPath = "/api/v1/registration-invitations";
            ApiResponse registrationInvitationResponse = apiRequest(
                    outsiderPage,
                    registrationInvitationsPath,
                    "POST",
                    outsiderToken,
                    null,
                    null);
            assertJsonStatus(registrationInvitationResponse, 201, registrationInvitationsPath);
            Map<String, Object> registrationInvitation = responseObject(registrationInvitationResponse);
            long registrationInvitationId = number(registrationInvitation.get("id"));
            assertEquals("registration", registrationInvitation.get("kind"));
            assertNull(registrationInvitation.get("calendarId"));
            OffsetDateTime registrationCreatedAt =
                    OffsetDateTime.parse(String.valueOf(registrationInvitation.get("createdAt")));
            OffsetDateTime registrationExpiresAt =
                    OffsetDateTime.parse(String.valueOf(registrationInvitation.get("expiresAt")));
            assertEquals(Duration.ofDays(7), Duration.between(registrationCreatedAt, registrationExpiresAt));
            assertTrue(registrationExpiresAt.isAfter(OffsetDateTime.now()));
            String registrationInvitationToken =
                    extractInvitationToken(String.valueOf(registrationInvitation.get("url")));
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from invitation where id = ? and created_by_user_id = ?",
                            registrationInvitationId,
                            outsiderId));
            assertEquals(
                    outsiderUsername,
                    responseObject(apiRequest(
                                    outsiderPage,
                                    "/api/v1/me",
                                    "GET",
                                    outsiderToken,
                                    null,
                                    null))
                            .get("username"));
            List<Map<String, Object>> invitationsBeforeWrongKindAcceptance = responseList(apiRequest(
                    outsiderPage, "/api/v1/invitations", "GET", outsiderToken, null, null));
            assertEquals(
                    1,
                    queryLong("select count(*) from invitation where id = ?", registrationInvitationId),
                    "Listing invitations must not delete an unexpired invitation.");
            Set<Long> invitationIdsBeforeWrongKindAcceptance = invitationsBeforeWrongKindAcceptance.stream()
                    .map(invitation -> number(invitation.get("id")))
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(
                    invitationIdsBeforeWrongKindAcceptance.contains(registrationInvitationId),
                    () -> "The creator's invitation list contained identifiers "
                            + invitationIdsBeforeWrongKindAcceptance
                            + ".");

            String invitationAcceptancesPath = "/api/v1/invitation-acceptances";
            assertProblem(
                    apiRequest(
                            firstAcceptorPage,
                            invitationAcceptancesPath,
                            "POST",
                            firstAcceptorToken,
                            null,
                            invitationAcceptanceInput(registrationInvitationToken)),
                    422,
                    "Validation failed",
                    invitationAcceptancesPath);
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from invitation where id = ? and created_by_user_id = ?",
                            registrationInvitationId,
                            outsiderId));

            List<Map<String, Object>> outsiderInvitations = responseList(apiRequest(
                    outsiderPage, "/api/v1/invitations", "GET", outsiderToken, null, null));
            assertEquals(registrationInvitationId, number(findObjectById(
                            outsiderInvitations, registrationInvitationId)
                    .get("id")));
            assertFalse(responseList(apiRequest(
                            adminPage, "/api/v1/invitations", "GET", adminToken, null, null))
                    .stream()
                    .anyMatch(invitation -> number(invitation.get("id")) == registrationInvitationId));

            String registrationInvitationPath = "/api/v1/invitations/" + registrationInvitationId;
            assertProblem(
                    apiRequest(
                            adminPage,
                            registrationInvitationPath,
                            "DELETE",
                            adminToken,
                            null,
                            null),
                    403,
                    "Forbidden",
                    registrationInvitationPath);
            assertEquals(1, queryLong("select count(*) from invitation where id = ?", registrationInvitationId));
            assertEquals(
                    204,
                    apiRequest(
                                    outsiderPage,
                                    registrationInvitationPath,
                                    "DELETE",
                                    outsiderToken,
                                    null,
                                    null)
                            .status());
            assertProblem(
                    apiRequest(
                            outsiderPage,
                            registrationInvitationPath,
                            "DELETE",
                            outsiderToken,
                            null,
                            null),
                    404,
                    "Not found",
                    registrationInvitationPath);

            String editorInvitationsPath =
                    "/api/v1/calendars/" + calendar.id() + "/editor-invitations";
            assertProblem(
                    apiRequest(
                            outsiderPage,
                            editorInvitationsPath,
                            "POST",
                            outsiderToken,
                            null,
                            null),
                    403,
                    "Forbidden",
                    editorInvitationsPath);

            ApiResponse editorCreatedInvitationResponse = apiRequest(
                    editorPage,
                    editorInvitationsPath,
                    "POST",
                    editorToken,
                    null,
                    null);
            assertJsonStatus(editorCreatedInvitationResponse, 201, editorInvitationsPath);
            Map<String, Object> editorCreatedInvitation = responseObject(editorCreatedInvitationResponse);
            long editorCreatedInvitationId = number(editorCreatedInvitation.get("id"));
            String editorCreatedInvitationToken =
                    extractInvitationToken(String.valueOf(editorCreatedInvitation.get("url")));
            assertEquals("calendar-editor", editorCreatedInvitation.get("kind"));
            assertEquals(calendar.id(), number(editorCreatedInvitation.get("calendarId")));

            Map<String, Object> adminVisibleEditorInvitation = findObjectById(
                    responseList(apiRequest(
                            adminPage, "/api/v1/invitations", "GET", adminToken, null, null)),
                    editorCreatedInvitationId);
            assertEquals("Invitation API calendar " + suffix, adminVisibleEditorInvitation.get("calendarName"));
            assertFalse(responseList(apiRequest(
                            outsiderPage, "/api/v1/invitations", "GET", outsiderToken, null, null))
                    .stream()
                    .anyMatch(invitation -> number(invitation.get("id")) == editorCreatedInvitationId));

            String editorCreatedInvitationPath = "/api/v1/invitations/" + editorCreatedInvitationId;
            assertEquals(
                    204,
                    apiRequest(
                                    adminPage,
                                    editorCreatedInvitationPath,
                                    "DELETE",
                                    adminToken,
                                    null,
                                    null)
                            .status());
            assertProblem(
                    apiRequest(
                            firstAcceptorPage,
                            invitationAcceptancesPath,
                            "POST",
                            firstAcceptorToken,
                            null,
                            invitationAcceptanceInput(editorCreatedInvitationToken)),
                    422,
                    "Validation failed",
                    invitationAcceptancesPath);

            ApiResponse permissionDependentInvitationResponse = apiRequest(
                    editorPage,
                    editorInvitationsPath,
                    "POST",
                    editorToken,
                    null,
                    null);
            long permissionDependentInvitationId =
                    number(responseObject(permissionDependentInvitationResponse).get("id"));
            String permissionDependentInvitationToken = extractInvitationToken(
                    String.valueOf(responseObject(permissionDependentInvitationResponse).get("url")));
            String editorMembershipPath = "/api/v1/calendars/"
                    + calendar.id()
                    + "/members/"
                    + editorId;
            assertEquals(
                    204,
                    apiRequest(
                                    adminPage,
                                    editorMembershipPath,
                                    "DELETE",
                                    adminToken,
                                    null,
                                    null)
                            .status());
            assertEquals(200, apiRequest(editorPage, "/api/v1/me", "GET", editorToken, null, null).status());
            assertProblem(
                    apiRequest(
                            editorPage,
                            editorInvitationsPath,
                            "POST",
                            editorToken,
                            null,
                            null),
                    403,
                    "Forbidden",
                    editorInvitationsPath);
            assertProblem(
                    apiRequest(
                            firstAcceptorPage,
                            invitationAcceptancesPath,
                            "POST",
                            firstAcceptorToken,
                            null,
                            invitationAcceptanceInput(permissionDependentInvitationToken)),
                    422,
                    "Validation failed",
                    invitationAcceptancesPath);
            assertEquals(1, queryLong("select count(*) from invitation where id = ?", permissionDependentInvitationId));

            ApiResponse expiredInvitationResponse = apiRequest(
                    adminPage,
                    editorInvitationsPath,
                    "POST",
                    adminToken,
                    null,
                    null);
            long expiredInvitationId = number(responseObject(expiredInvitationResponse).get("id"));
            String expiredInvitationToken =
                    extractInvitationToken(String.valueOf(responseObject(expiredInvitationResponse).get("url")));
            executeUpdate(
                    "update invitation set created_at = now() - interval '8 days', "
                            + "expires_at = now() - interval '1 day' where id = ?",
                    expiredInvitationId);
            assertProblem(
                    apiRequest(
                            firstAcceptorPage,
                            invitationAcceptancesPath,
                            "POST",
                            firstAcceptorToken,
                            null,
                            invitationAcceptanceInput(expiredInvitationToken)),
                    422,
                    "Validation failed",
                    invitationAcceptancesPath);

            ApiResponse concurrentInvitationResponse = apiRequest(
                    adminPage,
                    editorInvitationsPath,
                    "POST",
                    adminToken,
                    null,
                    null);
            long concurrentInvitationId = number(responseObject(concurrentInvitationResponse).get("id"));
            String concurrentInvitationToken =
                    extractInvitationToken(String.valueOf(responseObject(concurrentInvitationResponse).get("url")));
            List<ApiResponse> concurrentResponses = acceptInvitationConcurrently(
                    adminPage,
                    concurrentInvitationToken,
                    firstAcceptorToken,
                    secondAcceptorToken);
            assertEquals(
                    List.of(200, 422),
                    concurrentResponses.stream().map(ApiResponse::status).sorted().toList());
            int successfulResponseIndex = concurrentResponses.get(0).status() == 200 ? 0 : 1;
            int rejectedResponseIndex = 1 - successfulResponseIndex;
            long successfulUserId = successfulResponseIndex == 0 ? firstAcceptorId : secondAcceptorId;
            long rejectedUserId = successfulResponseIndex == 0 ? secondAcceptorId : firstAcceptorId;
            assertEquals("EDITOR", responseObject(concurrentResponses.get(successfulResponseIndex)).get("role"));
            assertNotNull(concurrentResponses.get(successfulResponseIndex).entityTag());
            assertProblem(
                    concurrentResponses.get(rejectedResponseIndex),
                    422,
                    "Validation failed",
                    invitationAcceptancesPath);
            assertEquals(
                    1,
                    queryLong(
                            "select count(*) from calendar_membership "
                                    + "where calendar_id = ? and user_id = ? and role_name = 'EDITOR'",
                            calendar.id(),
                            successfulUserId));
            assertEquals(
                    0,
                    queryLong(
                            "select count(*) from calendar_membership where calendar_id = ? and user_id = ?",
                            calendar.id(),
                            rejectedUserId));
            assertEquals(0, queryLong("select count(*) from invitation where id = ?", concurrentInvitationId));
            assertProblem(
                    apiRequest(
                            firstAcceptorPage,
                            invitationAcceptancesPath,
                            "POST",
                            firstAcceptorToken,
                            null,
                            invitationAcceptanceInput(concurrentInvitationToken)),
                    422,
                    "Validation failed",
                    invitationAcceptancesPath);
            assertEquals(0, queryLong(
                    "select count(*) from calendar_membership where calendar_id = ? and user_id = ?",
                    calendar.id(),
                    outsiderId));
        }
    }

    @Test
    void runtimeContractAndProblemResponsesRemainCompleteAndConsistent() throws SQLException {
        String suffix = uniqueSuffix();
        String username = "contract-user-" + suffix;
        String otherOwnerUsername = "contract-owner-" + suffix;
        long userId = seedUser(username, "Contract user " + suffix);
        long otherOwnerId = seedUser(otherOwnerUsername, "Contract owner " + suffix);
        SeededCalendar ownCalendar = seedCalendar(userId, "Contract calendar " + suffix);
        SeededCalendar foreignCalendar = seedCalendar(otherOwnerId, "Foreign contract calendar " + suffix);
        String token = seedApiToken(userId, "Contract token " + suffix);

        try (BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            navigate(page, "/sign-in");

            ApiResponse openApiResponse = apiRequest(page, "/api/openapi", "GET", null, null, null);
            assertJsonStatus(openApiResponse, 200, "/api/openapi");
            Map<String, Object> openApiDocument = responseObject(openApiResponse);
            assertEquals("3.1.0", openApiDocument.get("openapi"));
            assertEquals(EXPECTED_OPEN_API_OPERATIONS, collectOpenApiOperations(openApiDocument));

            List<Map<String, Object>> securityRequirements = objectList(openApiDocument.get("security"));
            assertEquals(1, securityRequirements.size());
            assertEquals(Set.of("bearerToken"), securityRequirements.getFirst().keySet());
            assertEquals(List.of(), securityRequirements.getFirst().get("bearerToken"));
            Map<String, Object> components = nestedObject(openApiDocument, "components");
            Map<String, Object> bearerScheme = nestedObject(
                    nestedObject(components, "securitySchemes"), "bearerToken");
            assertEquals("http", bearerScheme.get("type"));
            assertEquals("bearer", bearerScheme.get("scheme"));
            assertEquals("Opaque API token", bearerScheme.get("bearerFormat"));
            String bearerDescription = String.valueOf(bearerScheme.get("description"));
            assertTrue(bearerDescription.contains("Indefinite"));
            assertTrue(bearerDescription.contains("unscoped"));
            assertTrue(bearerDescription.contains("until revoked"));
            assertFalse(bearerScheme.containsKey("flows"));
            assertFalse(bearerScheme.containsKey("openIdConnectUrl"));
            assertEquals(
                    1,
                    operationParameters(openApiDocument, "/api/v1/calendars/{calendarId}", "put").size());
            assertEquals(
                    1,
                    operationParameters(
                                    openApiDocument,
                                    "/api/v1/calendars/{calendarId}/calendar-link-regenerations",
                                    "post")
                            .size());
            assertEquals(
                    1,
                    operationParameters(openApiDocument, "/api/v1/calendars/{calendarId}/events", "post")
                            .size());
            assertEquals(
                    1,
                    operationParameters(
                                    openApiDocument,
                                    "/api/v1/calendars/{calendarId}/events/{eventId}",
                                    "put")
                            .size());
            assertEquals(
                    1,
                    operationParameters(
                                    openApiDocument,
                                    "/api/v1/calendars/{calendarId}/events/{eventId}",
                                    "delete")
                            .size());

            int calendarCountBeforeFailures = Math.toIntExact(queryLong(
                    "select count(*) from calendar_membership where user_id = ?", userId));
            ApiResponse malformedJsonResponse = apiRequestWithHeaders(
                    page,
                    "/api/v1/calendars",
                    "POST",
                    bearerJsonHeaders(token),
                    "{",
                    false);
            assertProblem(malformedJsonResponse, 400, "Bad request", "/api/v1/calendars");
            assertUnauthorized(
                    apiRequest(page, "/api/v1/me", "GET", null, null, null),
                    "/api/v1/me");

            String foreignCalendarPath = "/api/v1/calendars/" + foreignCalendar.id();
            assertProblem(
                    apiRequest(page, foreignCalendarPath, "GET", token, null, null),
                    403,
                    "Forbidden",
                    foreignCalendarPath);
            String missingEventPath =
                    "/api/v1/calendars/" + ownCalendar.id() + "/events/999999999";
            assertProblem(
                    apiRequest(page, missingEventPath, "GET", token, null, null),
                    404,
                    "Not found",
                    missingEventPath);
            assertProblem(
                    apiRequestWithHeaders(
                            page,
                            "/api/v1/me",
                            "PATCH",
                            bearerJsonHeaders(token),
                            null,
                            false),
                    405,
                    "Method not allowed",
                    "/api/v1/me");
            assertProblem(
                    apiRequestWithHeaders(
                            page,
                            "/api/v1/me",
                            "GET",
                            Map.of("Accept", "text/plain", "Authorization", "Bearer " + token),
                            null,
                            false),
                    406,
                    "Not acceptable",
                    "/api/v1/me");
            assertProblem(
                    apiRequestWithHeaders(
                            page,
                            "/api/v1/calendars",
                            "POST",
                            Map.of(
                                    "Accept",
                                    "application/json",
                                    "Authorization",
                                    "Bearer " + token,
                                    "Content-Type",
                                    "text/plain"),
                            "{\"name\":\"Unsupported content type\"}",
                            false),
                    415,
                    "Unsupported media type",
                    "/api/v1/calendars");
            assertProblem(
                    apiRequest(
                            page,
                            "/api/v1/calendars",
                            "POST",
                            token,
                            null,
                            "{\"name\":\"   \"}"),
                    422,
                    "Validation failed",
                    "/api/v1/calendars");

            String ownCalendarPath = "/api/v1/calendars/" + ownCalendar.id();
            ApiResponse ownCalendarResponse =
                    apiRequest(page, ownCalendarPath, "GET", token, null, null);
            String unchangedCalendarSettings = calendarSettingsInput(
                    "Contract calendar " + suffix, null, "Europe/Warsaw", true);
            assertProblem(
                    apiRequest(
                            page,
                            ownCalendarPath,
                            "PUT",
                            token,
                            "\"999999\"",
                            unchangedCalendarSettings),
                    412,
                    "Precondition failed",
                    ownCalendarPath);
            assertProblem(
                    apiRequest(
                            page,
                            ownCalendarPath,
                            "PUT",
                            token,
                            null,
                            unchangedCalendarSettings),
                    428,
                    "Precondition required",
                    ownCalendarPath);

            assertNotNull(ownCalendarResponse.entityTag());
            assertEquals(
                    calendarCountBeforeFailures,
                    queryLong("select count(*) from calendar_membership where user_id = ?", userId));
            assertEquals(
                    "Contract calendar " + suffix,
                    queryText("select name from calendar where id = ?", ownCalendar.id()));
        }
    }

    private void submitApiTokenName(Page page, String tokenName) {
        Locator tokenNameInput = page.locator("input[id$='tokenName']");
        tokenNameInput.fill(tokenName);
        page.locator("button:has-text('Create API token')").click();
    }

    private String issueApiToken(Page page, String tokenName) {
        navigate(page, "/app/account-settings");
        submitApiTokenName(page, tokenName);
        assertThat(page.locator("body")).containsText("API token created.");
        String plaintextToken = page.getByLabel(
                        "New API token", new Page.GetByLabelOptions().setExact(true))
                .inputValue();
        assertTrue(
                API_TOKEN_PATTERN.matcher(plaintextToken).matches(),
                "The account settings form returned a malformed API token.");
        return plaintextToken;
    }

    private static Locator apiTokenCard(Page page, String tokenName) {
        return page.locator("article.list-card")
                .filter(new Locator.FilterOptions().setHasText(tokenName));
    }

    private void revokeApiToken(Page page, String tokenName) {
        navigate(page, "/app/account-settings");
        Locator tokenCard = apiTokenCard(page, tokenName);
        assertEquals(1, tokenCard.count(), "The token selected for revocation should be visible.");
        tokenCard.locator("button:has-text('Revoke')").click();
        page.locator(".ui-confirmdialog-yes").click();
    }

    private void submitRevocationWithTokenId(Page page, String visibleTokenName, long submittedTokenId) {
        navigate(page, "/app/account-settings");
        Locator tokenCard = apiTokenCard(page, visibleTokenName);
        assertEquals(1, tokenCard.count(), "The token used for the ownership check should be visible.");
        tokenCard.locator("input[name='apiTokenId']")
                .evaluate("(input, tokenId) => input.value = tokenId", Long.toString(submittedTokenId));
        tokenCard.locator("button:has-text('Revoke')").click();
        page.locator(".ui-confirmdialog-yes").click();
    }

    private static String calendarSettingsInput(
            String name,
            String description,
            String timeZone,
            boolean publicAccessEnabled) {
        return """
                {
                  "name": %s,
                  "description": %s,
                  "timeZone": %s,
                  "publicAccessEnabled": %s
                }
                """
                .formatted(
                        jsonValue(name),
                        jsonValue(description),
                        jsonValue(timeZone),
                        publicAccessEnabled);
    }

    private static String allDayEventInput(
            String title,
            String description,
            String location,
            String firstDay,
            String lastDay) {
        return """
                {
                  "title": %s,
                  "description": %s,
                  "location": %s,
                  "time": {
                    "kind": "all-day",
                    "firstDay": %s,
                    "lastDay": %s
                  }
                }
                """
                .formatted(
                        jsonValue(title),
                        jsonValue(description),
                        jsonValue(location),
                        jsonValue(firstDay),
                        jsonValue(lastDay));
    }

    private static String timedEventInput(
            String title,
            String description,
            String location,
            String startTime,
            String endTime) {
        return """
                {
                  "title": %s,
                  "description": %s,
                  "location": %s,
                  "time": {
                    "kind": "timed",
                    "start": %s,
                    "end": %s
                  }
                }
                """
                .formatted(
                        jsonValue(title),
                        jsonValue(description),
                        jsonValue(location),
                        jsonValue(startTime),
                        jsonValue(endTime));
    }

    private static String invitationAcceptanceInput(String invitationToken) {
        return "{\"token\":" + jsonValue(invitationToken) + "}";
    }

    private static String jsonValue(String value) {
        if (value == null) {
            return "null";
        }
        return "\""
                + value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t")
                + "\"";
    }

    private static ApiResponse apiRequest(
            Page page,
            String path,
            String method,
            String token,
            String entityTag,
            String requestBody) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        if (entityTag != null) {
            headers.put("If-Match", entityTag);
        }
        if (requestBody != null) {
            headers.put("Content-Type", "application/json");
        }
        return apiRequestWithHeaders(page, path, method, headers, requestBody, false);
    }

    private static ApiResponse cookieOnlyApiRequest(Page page, String path) {
        return apiRequestWithHeaders(
                page,
                path,
                "GET",
                Map.of("Accept", "application/json"),
                null,
                true);
    }

    private static Map<String, String> bearerJsonHeaders(String token) {
        return Map.of(
                "Accept", "application/json",
                "Authorization", "Bearer " + token,
                "Content-Type", "application/json");
    }

    @SuppressWarnings("unchecked")
    private static ApiResponse apiRequestWithHeaders(
            Page page,
            String path,
            String method,
            Map<String, String> headers,
            String requestBody,
            boolean includeCredentials) {
        Map<String, Object> request = new HashMap<>();
        request.put("path", path);
        request.put("method", method);
        request.put("headers", headers);
        request.put("requestBody", requestBody);
        request.put("includeCredentials", includeCredentials);
        Map<String, Object> rawResponse = (Map<String, Object>) page.evaluate(
                """
                async request => {
                    const response = await fetch(request.path, {
                        method: request.method,
                        headers: request.headers,
                        body: request.requestBody,
                        credentials: request.includeCredentials ? "same-origin" : "omit"
                    });
                    const rawBody = response.status === 204 ? "" : await response.text();
                    let body = null;
                    if (rawBody.length > 0) {
                        try {
                            body = JSON.parse(rawBody);
                        } catch (error) {
                            body = rawBody;
                        }
                    }
                    return {
                        status: response.status,
                        contentType: response.headers.get("content-type"),
                        etag: response.headers.get("etag"),
                        location: response.headers.get("location"),
                        wwwAuthenticate: response.headers.get("www-authenticate"),
                        rawBody,
                        body
                    };
                }
                """,
                request);
        return apiResponse(rawResponse);
    }

    @SuppressWarnings("unchecked")
    private static List<ApiResponse> acceptInvitationConcurrently(
            Page page,
            String invitationToken,
            String firstApiToken,
            String secondApiToken) {
        Map<String, Object> request = new HashMap<>();
        request.put("path", "/api/v1/invitation-acceptances");
        request.put("invitationToken", invitationToken);
        request.put("apiTokens", List.of(firstApiToken, secondApiToken));
        List<Map<String, Object>> rawResponses = (List<Map<String, Object>>) page.evaluate(
                """
                async request => Promise.all(request.apiTokens.map(async apiToken => {
                    const response = await fetch(request.path, {
                        method: "POST",
                        headers: {
                            Accept: "application/json",
                            Authorization: `Bearer ${apiToken}`,
                            "Content-Type": "application/json"
                        },
                        body: JSON.stringify({token: request.invitationToken}),
                        credentials: "omit"
                    });
                    const rawBody = await response.text();
                    let body = null;
                    if (rawBody.length > 0) {
                        try {
                            body = JSON.parse(rawBody);
                        } catch (error) {
                            body = rawBody;
                        }
                    }
                    return {
                        status: response.status,
                        contentType: response.headers.get("content-type"),
                        etag: response.headers.get("etag"),
                        location: response.headers.get("location"),
                        wwwAuthenticate: response.headers.get("www-authenticate"),
                        rawBody,
                        body
                    };
                }));
                """,
                request);
        List<ApiResponse> responses = new ArrayList<>(rawResponses.size());
        for (Map<String, Object> rawResponse : rawResponses) {
            responses.add(apiResponse(rawResponse));
        }
        return List.copyOf(responses);
    }

    private static ApiResponse apiResponse(Map<String, Object> rawResponse) {
        return new ApiResponse(
                ((Number) rawResponse.get("status")).intValue(),
                (String) rawResponse.get("contentType"),
                (String) rawResponse.get("etag"),
                (String) rawResponse.get("location"),
                (String) rawResponse.get("wwwAuthenticate"),
                String.valueOf(rawResponse.get("rawBody")),
                rawResponse.get("body"));
    }

    private static void assertJsonStatus(ApiResponse response, int expectedStatus, String path) {
        assertEquals(
                expectedStatus,
                response.status(),
                () -> "Expected " + expectedStatus + " from " + path + " but received " + response.status() + ".");
        assertTrue(
                response.contentType() != null && response.contentType().startsWith("application/json"),
                () -> path + " should return application/json but returned " + response.contentType() + ".");
        assertNotNull(response.body(), () -> path + " should return a JSON body.");
    }

    private static void assertUnauthorized(ApiResponse response, String path) {
        assertProblem(response, 401, "Unauthorized", path);
        assertEquals("Bearer realm=\"calendar.social\"", response.wwwAuthenticate());
    }

    private static void assertProblem(
            ApiResponse response,
            int expectedStatus,
            String expectedTitle,
            String expectedInstance) {
        assertEquals(
                expectedStatus,
                response.status(),
                () -> "Expected "
                        + expectedStatus
                        + " from "
                        + expectedInstance
                        + " but received "
                        + response.status()
                        + ".");
        assertTrue(
                response.contentType() != null
                        && response.contentType().startsWith("application/problem+json"),
                () -> expectedInstance
                        + " should return application/problem+json but returned "
                        + response.contentType()
                        + ".");
        Map<String, Object> body = responseObject(response);
        assertEquals(
                Set.of("type", "title", "status", "detail", "instance"),
                body.keySet(),
                "Problem responses should use one complete, uniform envelope.");
        assertEquals("about:blank", body.get("type"));
        assertEquals(expectedTitle, body.get("title"));
        assertEquals(expectedStatus, ((Number) body.get("status")).intValue());
        assertTrue(
                body.get("detail") instanceof String detail && !detail.isBlank(),
                "Problem responses should include a useful detail.");
        assertEquals(expectedInstance, body.get("instance"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responseObject(ApiResponse response) {
        assertTrue(response.body() instanceof Map<?, ?>, "The API response body should be a JSON object.");
        return (Map<String, Object>) response.body();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> responseList(ApiResponse response) {
        assertTrue(response.body() instanceof List<?>, "The API response body should be a JSON array.");
        return (List<Map<String, Object>>) response.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedObject(Map<String, Object> parent, String propertyName) {
        Object nestedValue = parent.get(propertyName);
        assertTrue(nestedValue instanceof Map<?, ?>, propertyName + " should be a JSON object.");
        return (Map<String, Object>) nestedValue;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        assertTrue(value instanceof List<?>, "The OpenAPI value should be an array.");
        return (List<Map<String, Object>>) value;
    }

    private static long number(Object value) {
        assertTrue(value instanceof Number, "The JSON value should be numeric.");
        return ((Number) value).longValue();
    }

    private static Map<String, Object> findObjectById(
            List<Map<String, Object>> objects,
            long expectedId) {
        return objects.stream()
                .filter(object -> number(object.get("id")) == expectedId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("JSON array did not contain identifier " + expectedId + "."));
    }

    private static Map<String, Object> findMembershipByUserId(
            List<Map<String, Object>> memberships,
            long expectedUserId) {
        return memberships.stream()
                .filter(membership -> number(nestedObject(membership, "user").get("id")) == expectedUserId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Membership array did not contain user " + expectedUserId + "."));
    }

    private static String extractInvitationToken(String invitationUrl) {
        String query = URI.create(invitationUrl).getRawQuery();
        assertNotNull(query, "Invitation URL should contain a token query parameter.");
        for (String parameter : query.split("&")) {
            if (parameter.startsWith("token=")) {
                String token = parameter.substring("token=".length());
                assertEquals(43, token.length(), "Invitation tokens should use the documented format.");
                return token;
            }
        }
        throw new AssertionError("Invitation URL did not contain a token query parameter.");
    }

    private static Set<String> collectOpenApiOperations(Map<String, Object> openApiDocument) {
        Set<String> operations = new LinkedHashSet<>();
        Map<String, Object> paths = nestedObject(openApiDocument, "paths");
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pathItem = (Map<String, Object>) pathEntry.getValue();
            for (Map.Entry<String, Object> operationEntry : pathItem.entrySet()) {
                if (!HTTP_OPERATION_NAMES.contains(operationEntry.getKey())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> operation = (Map<String, Object>) operationEntry.getValue();
                operations.add(operationEntry.getKey().toUpperCase()
                        + " "
                        + pathEntry.getKey()
                        + " "
                        + operation.get("operationId"));
            }
        }
        return Set.copyOf(operations);
    }

    private static List<Map<String, Object>> operationParameters(
            Map<String, Object> openApiDocument,
            String path,
            String method) {
        Map<String, Object> operation = nestedObject(
                nestedObject(nestedObject(openApiDocument, "paths"), path), method);
        return objectList(operation.get("parameters"));
    }

    private record ApiResponse(
            int status,
            String contentType,
            String entityTag,
            String location,
            String wwwAuthenticate,
            String rawBody,
            Object body) {}
}
