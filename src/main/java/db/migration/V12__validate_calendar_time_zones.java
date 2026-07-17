package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V12__validate_calendar_time_zones extends BaseJavaMigration {
    private static final Set<String> SUPPORTED_TIME_ZONES = ZoneId.getAvailableZoneIds();

    @Override
    public void migrate(Context context) throws Exception {
        List<String> storedTimeZones = findStoredTimeZones(context.getConnection());
        Set<String> unsupportedTimeZones = unsupportedTimeZones(storedTimeZones);
        if (!unsupportedTimeZones.isEmpty()) {
            throw new IllegalStateException(
                    "Calendars contain unsupported time zones: " + String.join(", ", unsupportedTimeZones));
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

    private List<String> findStoredTimeZones(Connection connection) throws Exception {
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
