package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import db.migration.CalendarTimeZoneAudit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class DatabaseMigrationEndToEndIT {
    @Test
    void preMigrationTimeZoneAuditSkipsAFreshSchemaAndRejectsLegacyOffsetZones()
            throws Exception {
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("set local search_path to pg_temp");
                }
                CalendarTimeZoneAudit.validateBeforeMigration(connection);

                try (Statement statement = connection.createStatement()) {
                    statement.execute("create temporary table calendar (timezone varchar(80) not null)");
                    statement.execute("insert into calendar (timezone) values ('Europe/Warsaw'), ('+02:00')");
                }

                IllegalStateException exception = assertThrows(
                        IllegalStateException.class,
                        () -> CalendarTimeZoneAudit.validateBeforeMigration(connection));
                assertEquals(
                        "Calendars contain unsupported time zones: +02:00",
                        exception.getMessage());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void versionFiveNormalizesRealPostgresqlBoundariesWithoutTouchingOtherRows()
            throws Exception {
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try {
                createMigrationFixtureTables(connection);
                insertMigrationFixtures(connection);
                String migrationSql = Files.readString(Path.of(
                        "src",
                        "main",
                        "resources",
                        "db",
                        "migration",
                        "V5__normalize_all_day_event_boundaries.sql"));
                try (Statement statement = connection.createStatement()) {
                    statement.execute(migrationSql);
                }

                assertBoundary(
                        connection,
                        101L,
                        Instant.parse("2026-03-28T23:00:00Z"),
                        Instant.parse("2026-03-29T22:00:00Z"));
                assertBoundary(
                        connection,
                        102L,
                        Instant.parse("2026-07-22T00:00:00Z"),
                        Instant.parse("2026-07-23T00:00:00Z"));
                assertBoundary(
                        connection,
                        103L,
                        Instant.parse("2026-07-22T10:15:00Z"),
                        Instant.parse("2026-07-22T11:15:00Z"));
                assertBoundary(
                        connection,
                        104L,
                        Instant.parse("2026-07-22T10:00:00Z"),
                        Instant.parse("2026-07-22T11:00:00Z"));
            } finally {
                connection.rollback();
            }
        }
    }

    private static void createMigrationFixtureTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create temporary table calendar ("
                    + "id bigint primary key, timezone varchar(80) not null)");
            statement.execute("create temporary table calendar_event ("
                    + "id bigint primary key, calendar_id bigint not null, "
                    + "start_at timestamptz not null, end_at timestamptz not null, "
                    + "all_day boolean not null)");
        }
    }

    private static void insertMigrationFixtures(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into calendar (id, timezone) values "
                    + "(1, 'Europe/Warsaw'), (2, 'UTC'), (3, '+02:00')");
            statement.executeUpdate("insert into calendar_event "
                    + "(id, calendar_id, start_at, end_at, all_day) values "
                    + "(101, 1, '2026-03-29 12:00:00+02', '2026-03-29 13:00:00+02', true), "
                    + "(102, 2, '2026-07-22 12:00:00+00', '2026-07-20 12:00:00+00', true), "
                    + "(103, 2, '2026-07-22 10:15:00+00', '2026-07-22 11:15:00+00', false), "
                    + "(104, 3, '2026-07-22 12:00:00+02', '2026-07-22 13:00:00+02', true)");
        }
    }

    private static void assertBoundary(
            Connection connection,
            long eventId,
            Instant expectedStart,
            Instant expectedEnd) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                        "select start_at, end_at from calendar_event where id = ?")) {
            statement.setLong(1, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "Expected migration fixture event " + eventId + ".");
                assertEquals(
                        expectedStart,
                        resultSet.getObject("start_at", OffsetDateTime.class).toInstant());
                assertEquals(
                        expectedEnd,
                        resultSet.getObject("end_at", OffsetDateTime.class).toInstant());
            }
        }
    }

    private static Connection databaseConnection() throws Exception {
        String host = environmentValueOrDefault("PGHOST", "localhost");
        String port = environmentValueOrDefault("PGPORT", "5432");
        String database = environmentValueOrDefault("PGDATABASE", "calendar");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + database,
                environmentValueOrDefault("PGUSER", "calendar"),
                environmentValueOrDefault("PGPASSWORD", "calendar"));
    }

    private static String environmentValueOrDefault(String variableName, String defaultValue) {
        String value = System.getenv(variableName);
        return value == null || value.isBlank()
                ? defaultValue
                : value.trim();
    }
}
