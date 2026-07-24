package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class CalendarTimeZoneAudit {
    private static final Set<String> SUPPORTED_TIME_ZONES = ZoneId.getAvailableZoneIds();

    private CalendarTimeZoneAudit() {
    }

    public static void validateBeforeMigration(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "Database connection is required.");
        if (!calendarTableExists(connection)) {
            return;
        }
        validateStoredTimeZones(connection);
    }

    public static void validateStoredTimeZones(Connection connection) throws SQLException {
        Set<String> unsupportedTimeZones = unsupportedTimeZones(findStoredTimeZones(connection));
        if (!unsupportedTimeZones.isEmpty()) {
            throw new IllegalStateException(
                    "Calendars contain unsupported time zones: "
                            + String.join(", ", unsupportedTimeZones));
        }
    }

    static Set<String> unsupportedTimeZones(Collection<String> timeZones) {
        Set<String> unsupportedTimeZones = new TreeSet<>();
        for (String timeZone : timeZones) {
            if (timeZone == null
                    || !timeZone.equals(timeZone.trim())
                    || !SUPPORTED_TIME_ZONES.contains(timeZone)) {
                unsupportedTimeZones.add(String.valueOf(timeZone));
            }
        }
        return unsupportedTimeZones;
    }

    private static boolean calendarTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select to_regclass('calendar') is not null");
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("Calendar table existence query returned no result.");
            }
            return resultSet.getBoolean(1);
        }
    }

    private static List<String> findStoredTimeZones(Connection connection) throws SQLException {
        List<String> storedTimeZones = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                        "select distinct timezone from calendar order by timezone");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                storedTimeZones.add(resultSet.getString("timezone"));
            }
        }
        return storedTimeZones;
    }
}
