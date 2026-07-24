package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V7__normalize_all_day_events_for_java_time_zones extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        List<AllDayEventBoundary> eventBoundaries = findEventBoundariesForJavaOnlyTimeZones(connection);
        try (PreparedStatement updateStatement = connection.prepareStatement(
                "update calendar_event set start_at = ?, end_at = ? where id = ?")) {
            for (AllDayEventBoundary eventBoundary : eventBoundaries) {
                NormalizedBoundary normalizedBoundary = normalizeBoundary(
                        eventBoundary.startTime(),
                        eventBoundary.endTime(),
                        eventBoundary.timeZone());
                updateStatement.setObject(1, normalizedBoundary.startTime());
                updateStatement.setObject(2, normalizedBoundary.endTime());
                updateStatement.setLong(3, eventBoundary.eventId());
                updateStatement.addBatch();
            }
            updateStatement.executeBatch();
        }
    }

    static NormalizedBoundary normalizeBoundary(
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String timeZone) {
        ZoneId zoneId = ZoneId.of(timeZone);
        LocalDate firstDay = startTime.atZoneSameInstant(zoneId).toLocalDate();
        LocalDate finalDay = endTime.atZoneSameInstant(zoneId).toLocalDate();
        if (finalDay.isBefore(firstDay)) {
            finalDay = firstDay;
        }

        return new NormalizedBoundary(
                startOfDay(firstDay, zoneId),
                startOfDay(finalDay.plusDays(1), zoneId));
    }

    private static List<AllDayEventBoundary> findEventBoundariesForJavaOnlyTimeZones(Connection connection)
            throws SQLException {
        List<AllDayEventBoundary> eventBoundaries = new ArrayList<>();
        try (PreparedStatement queryStatement = connection.prepareStatement(
                        "select calendar_event.id, calendar_event.start_at, calendar_event.end_at, calendar.timezone "
                                + "from calendar_event "
                                + "join calendar on calendar.id = calendar_event.calendar_id "
                                + "where calendar_event.all_day = true "
                                + "and not exists (select 1 from pg_timezone_names "
                                + "where pg_timezone_names.name = calendar.timezone)");
                ResultSet resultSet = queryStatement.executeQuery()) {
            while (resultSet.next()) {
                eventBoundaries.add(new AllDayEventBoundary(
                        resultSet.getLong("id"),
                        resultSet.getObject("start_at", OffsetDateTime.class),
                        resultSet.getObject("end_at", OffsetDateTime.class),
                        resultSet.getString("timezone")));
            }
        }
        return eventBoundaries;
    }

    private static OffsetDateTime startOfDay(LocalDate date, ZoneId zoneId) {
        ZonedDateTime startOfDay = date.atStartOfDay(zoneId);
        if (!startOfDay.toLocalDate().equals(date)) {
            throw new IllegalStateException(
                    "An all-day event uses a civil date that does not exist in its calendar time zone.");
        }
        return startOfDay.toOffsetDateTime();
    }

    private record AllDayEventBoundary(
            long eventId,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String timeZone) {
    }

    record NormalizedBoundary(OffsetDateTime startTime, OffsetDateTime endTime) {
    }
}
