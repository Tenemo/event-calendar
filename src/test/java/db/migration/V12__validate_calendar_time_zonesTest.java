package db.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class V12__validate_calendar_time_zonesTest {
    @Test
    void acceptsSupportedIdentifiersButRejectsOffsetsWhitespaceAndUnknownStoredValues() {
        assertEquals(
                Set.of("+01:00", "Europe/Warsaw ", "Mars/Olympus", "UTC+01:00", "Z", "null"),
                CalendarTimeZoneAudit.unsupportedTimeZones(Arrays.asList(
                        "Europe/Warsaw",
                        "America/New_York",
                        "Pacific/Apia",
                        "UTC",
                        "GMT",
                        "Etc/UTC",
                        "+01:00",
                        "UTC+01:00",
                        "Z",
                        "Europe/Warsaw ",
                        "Mars/Olympus",
                        null)));
    }

    @Test
    void preMigrationAuditSkipsAnEmptyDatabaseButRejectsLegacyOffsetZones() throws Exception {
        AtomicInteger emptyDatabaseTimeZoneQueries = new AtomicInteger();
        CalendarTimeZoneAudit.validateBeforeMigration(
                connection(false, List.of(), emptyDatabaseTimeZoneQueries));

        AtomicInteger existingDatabaseTimeZoneQueries = new AtomicInteger();
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> CalendarTimeZoneAudit.validateBeforeMigration(
                        connection(
                                true,
                                List.of("Europe/Warsaw", "+02:00"),
                                existingDatabaseTimeZoneQueries)));

        assertEquals(0, emptyDatabaseTimeZoneQueries.get());
        assertEquals(1, existingDatabaseTimeZoneQueries.get());
        assertEquals(
                "Calendars contain unsupported time zones: +02:00",
                exception.getMessage());
    }

    private static Connection connection(
            boolean calendarTableExists,
            List<String> storedTimeZones,
            AtomicInteger timeZoneQueryCount) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement")) {
                        String query = (String) arguments[0];
                        if (query.contains("to_regclass")) {
                            return statement(List.of(calendarTableExists), false, timeZoneQueryCount);
                        }
                        if (query.contains("select distinct timezone")) {
                            return statement(storedTimeZones, true, timeZoneQueryCount);
                        }
                        throw new AssertionError("Unexpected query: " + query);
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static PreparedStatement statement(
            List<?> rows,
            boolean timeZoneQuery,
            AtomicInteger timeZoneQueryCount) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("executeQuery")) {
                        if (timeZoneQuery) {
                            timeZoneQueryCount.incrementAndGet();
                        }
                        return resultSet(rows, timeZoneQuery);
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static ResultSet resultSet(List<?> rows, boolean timeZoneRows) {
        AtomicInteger rowIndex = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> rowIndex.incrementAndGet() < rows.size();
                    case "getBoolean" -> rows.get(rowIndex.get());
                    case "getString" -> timeZoneRows ? rows.get(rowIndex.get()) : null;
                    case "close" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return 0;
    }
}
