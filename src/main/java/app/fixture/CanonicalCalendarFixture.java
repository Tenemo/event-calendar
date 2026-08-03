package app.fixture;

import app.calendar.CalendarLinkToken;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CanonicalCalendarFixture {
    public static final String FRIENDS_CALENDAR_NAME = "Friends and birthdays";
    public static final String WEEKEND_CALENDAR_NAME = "Weekend adventures";
    public static final String LISBON_CALENDAR_NAME = "Lisbon long weekend";

    private static final String FIXTURE_RESOURCE = "/db/fixture/canonical-calendar-fixture.sql";
    private static final int EXPECTED_USER_COUNT = 3;
    private static final int EXPECTED_CALENDAR_COUNT = 3;
    private static final int EXPECTED_MEMBERSHIP_COUNT = 7;
    private static final int EXPECTED_EVENT_COUNT = 25;
    private static final int EXPECTED_ALL_DAY_EVENT_COUNT = 6;
    private static final int EXPECTED_WEEKEND_EVENT_COUNT = 15;
    private static final String FIXTURE_SQL = readFixtureSql();

    private CanonicalCalendarFixture() {
    }

    public static void install(Connection connection, Installation installation) throws SQLException {
        Objects.requireNonNull(connection, "A database connection is required.");
        Objects.requireNonNull(installation, "Fixture installation settings are required.");

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("calendar.fixture.scope", installation.scope().name().toLowerCase(Locale.ROOT));
        settings.put("calendar.fixture.owner_username", installation.ownerUsername());
        settings.put("calendar.fixture.owner_display_name", installation.ownerDisplayName());
        settings.put("calendar.fixture.owner_password_hash", installation.ownerPasswordHash());
        settings.put("calendar.fixture.owner_exists", Boolean.toString(installation.ownerAlreadyExists()));
        settings.put("calendar.fixture.maya_username", installation.mayaUsername());
        settings.put("calendar.fixture.maya_password_hash", installation.mayaPasswordHash());
        settings.put("calendar.fixture.maya_exists", Boolean.toString(installation.mayaAlreadyExists()));
        settings.put("calendar.fixture.tomasz_username", installation.tomaszUsername());
        settings.put("calendar.fixture.tomasz_password_hash", installation.tomaszPasswordHash());
        settings.put("calendar.fixture.tomasz_exists", Boolean.toString(installation.tomaszAlreadyExists()));
        settings.put("calendar.fixture.friends_token", installation.friendsCalendarLinkToken());
        settings.put("calendar.fixture.weekend_token", installation.weekendCalendarLinkToken());
        settings.put("calendar.fixture.lisbon_token", installation.lisbonCalendarLinkToken());
        settings.put("calendar.fixture.bootstrap_consumed_at", installation.bootstrapConsumedAt().toString());

        try (PreparedStatement statement =
                connection.prepareStatement("select set_config(?, ?, true)")) {
            for (Map.Entry<String, String> setting : settings.entrySet()) {
                statement.setString(1, setting.getKey());
                statement.setString(2, setting.getValue());
                statement.execute();
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(FIXTURE_SQL);
        }
    }

    public static FixtureSummary readSummary(Connection connection, Installation installation)
            throws SQLException {
        String summaryQuery = """
                select
                    (select count(*) from app_user
                        where username in (?, ?, ?)),
                    (select count(*) from calendar
                        where calendar_link_token in (?, ?, ?)),
                    (select count(*) from calendar_membership membership
                        join calendar membership_calendar on membership_calendar.id = membership.calendar_id
                        where membership_calendar.calendar_link_token in (?, ?, ?)),
                    (select count(*) from calendar_event event
                        join calendar event_calendar on event_calendar.id = event.calendar_id
                        where event_calendar.calendar_link_token in (?, ?, ?)),
                    (select count(*) from calendar_event event
                        join calendar event_calendar on event_calendar.id = event.calendar_id
                        where event_calendar.calendar_link_token in (?, ?, ?)
                            and event.all_day),
                    (select count(*) from calendar_event event
                        join calendar event_calendar on event_calendar.id = event.calendar_id
                        where event_calendar.calendar_link_token = ?),
                    (select count(*) from calendar_event event
                        join calendar event_calendar on event_calendar.id = event.calendar_id
                        where event_calendar.calendar_link_token = ?
                            and event.title = 'Skiing in Italy'
                            and event.start_at = timestamptz '2027-01-15 23:00:00+00'
                            and event.end_at = timestamptz '2027-01-23 23:00:00+00'
                            and event.all_day),
                    (select count(*) from calendar
                        where calendar_link_token in (?, ?, ?)
                            and public_access_enabled),
                    (select count(distinct time_zone) from calendar
                        where calendar_link_token in (?, ?, ?)),
                    (select count(*) from invitation invitation
                        left join calendar invitation_calendar on invitation_calendar.id = invitation.calendar_id
                        join app_user creator on creator.id = invitation.created_by_user_id
                        where invitation_calendar.calendar_link_token in (?, ?, ?)
                            or creator.username in (?, ?, ?)),
                    (select count(*) from registration_bootstrap
                        where singleton_id = 1 and consumed_at is not null)
                """;
        try (PreparedStatement statement = connection.prepareStatement(summaryQuery)) {
            int parameterIndex = 1;
            parameterIndex = setStrings(
                    statement,
                    parameterIndex,
                    installation.ownerUsername(),
                    installation.mayaUsername(),
                    installation.tomaszUsername());
            for (int repetition = 0; repetition < 4; repetition++) {
                parameterIndex = setCalendarLinkTokens(statement, parameterIndex, installation);
            }
            statement.setString(parameterIndex++, installation.weekendCalendarLinkToken());
            statement.setString(parameterIndex++, installation.weekendCalendarLinkToken());
            parameterIndex = setCalendarLinkTokens(statement, parameterIndex, installation);
            parameterIndex = setCalendarLinkTokens(statement, parameterIndex, installation);
            parameterIndex = setCalendarLinkTokens(statement, parameterIndex, installation);
            setStrings(
                    statement,
                    parameterIndex,
                    installation.ownerUsername(),
                    installation.mayaUsername(),
                    installation.tomaszUsername());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("The canonical fixture summary query returned no row.");
                }
                return new FixtureSummary(
                        resultSet.getInt(1),
                        resultSet.getInt(2),
                        resultSet.getInt(3),
                        resultSet.getInt(4),
                        resultSet.getInt(5),
                        resultSet.getInt(6),
                        resultSet.getInt(7),
                        resultSet.getInt(8),
                        resultSet.getInt(9),
                        resultSet.getInt(10),
                        resultSet.getInt(11));
            }
        }
    }

    private static int setCalendarLinkTokens(
            PreparedStatement statement,
            int parameterIndex,
            Installation installation) throws SQLException {
        return setStrings(
                statement,
                parameterIndex,
                installation.friendsCalendarLinkToken(),
                installation.weekendCalendarLinkToken(),
                installation.lisbonCalendarLinkToken());
    }

    private static int setStrings(
            PreparedStatement statement,
            int parameterIndex,
            String... values) throws SQLException {
        int nextParameterIndex = parameterIndex;
        for (String value : values) {
            statement.setString(nextParameterIndex++, value);
        }
        return nextParameterIndex;
    }

    private static String readFixtureSql() {
        try (InputStream inputStream =
                CanonicalCalendarFixture.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Canonical calendar fixture SQL is missing from the classpath.");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Canonical calendar fixture SQL could not be read.", exception);
        }
    }

    public enum Scope {
        LOCAL,
        PREVIEW,
        PRODUCTION
    }

    public record Installation(
            Scope scope,
            String ownerUsername,
            String ownerDisplayName,
            String ownerPasswordHash,
            boolean ownerAlreadyExists,
            String mayaUsername,
            String mayaPasswordHash,
            boolean mayaAlreadyExists,
            String tomaszUsername,
            String tomaszPasswordHash,
            boolean tomaszAlreadyExists,
            String friendsCalendarLinkToken,
            String weekendCalendarLinkToken,
            String lisbonCalendarLinkToken,
            OffsetDateTime bootstrapConsumedAt) {
        public Installation {
            Objects.requireNonNull(scope, "Fixture scope is required.");
            requireAccount(ownerUsername, ownerDisplayName, ownerPasswordHash, "owner");
            requireAccount(mayaUsername, "Maya Nowak", mayaPasswordHash, "Maya");
            requireAccount(tomaszUsername, "Tomasz Zieliński", tomaszPasswordHash, "Tomasz");
            if (Set.of(ownerUsername, mayaUsername, tomaszUsername).size() != EXPECTED_USER_COUNT) {
                throw new IllegalArgumentException("Canonical fixture usernames must be distinct.");
            }
            requireCalendarLinkToken(friendsCalendarLinkToken, "friends and birthdays");
            requireCalendarLinkToken(weekendCalendarLinkToken, "weekend adventures");
            requireCalendarLinkToken(lisbonCalendarLinkToken, "Lisbon long weekend");
            if (Set.of(
                            friendsCalendarLinkToken,
                            weekendCalendarLinkToken,
                            lisbonCalendarLinkToken)
                    .size()
                    != EXPECTED_CALENDAR_COUNT) {
                throw new IllegalArgumentException("Canonical fixture calendar links must be distinct.");
            }
            Objects.requireNonNull(bootstrapConsumedAt, "Bootstrap consumption time is required.");
        }

        private static void requireAccount(
                String username,
                String displayName,
                String passwordHash,
                String accountName) {
            if (username == null || username.isBlank() || username.length() > 80) {
                throw new IllegalArgumentException(
                        "The canonical " + accountName + " username must contain between 1 and 80 characters.");
            }
            if (displayName == null || displayName.isBlank() || displayName.length() > 160) {
                throw new IllegalArgumentException(
                        "The canonical " + accountName + " display name must contain between 1 and 160 characters.");
            }
            if (passwordHash == null || passwordHash.isBlank()) {
                throw new IllegalArgumentException(
                        "The canonical " + accountName + " password hash is required.");
            }
        }

        private static void requireCalendarLinkToken(String token, String calendarName) {
            if (!CalendarLinkToken.isValid(token)) {
                throw new IllegalArgumentException(
                        "The canonical " + calendarName + " calendar link is invalid.");
            }
        }

        @Override
        public String toString() {
            return "Installation[scope="
                    + scope
                    + ", ownerUsername="
                    + ownerUsername
                    + ", ownerDisplayName="
                    + ownerDisplayName
                    + ", ownerPasswordHash=redacted, ownerAlreadyExists="
                    + ownerAlreadyExists
                    + ", mayaUsername="
                    + mayaUsername
                    + ", mayaPasswordHash=redacted, mayaAlreadyExists="
                    + mayaAlreadyExists
                    + ", tomaszUsername="
                    + tomaszUsername
                    + ", tomaszPasswordHash=redacted, tomaszAlreadyExists="
                    + tomaszAlreadyExists
                    + ", calendarLinkTokens=redacted, bootstrapConsumedAt="
                    + bootstrapConsumedAt
                    + "]";
        }
    }

    public record FixtureSummary(
            int userCount,
            int calendarCount,
            int membershipCount,
            int eventCount,
            int allDayEventCount,
            int weekendAdventureEventCount,
            int italySkiTripCount,
            int publicCalendarCount,
            int timeZoneCount,
            int invitationCount,
            int consumedBootstrapCount) {
        public void verifyExpectedFixtureShape() {
            if (userCount != EXPECTED_USER_COUNT
                    || calendarCount != EXPECTED_CALENDAR_COUNT
                    || membershipCount != EXPECTED_MEMBERSHIP_COUNT
                    || eventCount != EXPECTED_EVENT_COUNT
                    || allDayEventCount != EXPECTED_ALL_DAY_EVENT_COUNT
                    || weekendAdventureEventCount != EXPECTED_WEEKEND_EVENT_COUNT
                    || italySkiTripCount != 1
                    || publicCalendarCount != 2
                    || timeZoneCount != 2
                    || invitationCount != 0
                    || consumedBootstrapCount != 1) {
                throw new IllegalStateException(
                        "The canonical calendar fixture does not have its expected deterministic shape.");
            }
        }
    }
}
