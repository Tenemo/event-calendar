package app.startup;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

final class DatabaseMigrationResourcesTest {
    private static final Path DATABASE_RESOURCE_DIRECTORY = Path.of("src", "main", "resources", "db");

    @Test
    void libertyClasspathLocationMarkerIsPackagedWithFlywayMigrations() {
        assertTrue(
                Files.isRegularFile(DATABASE_RESOURCE_DIRECTORY.resolve("migration/flyway.location")),
                "Open Liberty requires a marker to discover Flyway migrations on the classpath.");
    }

    @Test
    void canonicalCalendarTokenConstraintUsesSeparateLowLockMigrationPhases() throws IOException {
        String stagedConstraintMigration = migrationSql(
                "V13__reapply_canonical_calendar_link_token_constraint.sql");
        String validationMigration = migrationSql(
                "V14__validate_canonical_calendar_link_token_constraint.sql");
        String replacementMigration = migrationSql(
                "V15__replace_canonical_calendar_link_token_constraint.sql");

        assertAll(
                () -> assertTrue(stagedConstraintMigration.contains("set local lock_timeout = '10s'")),
                () -> assertTrue(stagedConstraintMigration.contains("not valid")),
                () -> assertFalse(stagedConstraintMigration.contains("validate constraint")),
                () -> assertFalse(stagedConstraintMigration.contains("drop constraint")),
                () -> assertTrue(validationMigration.contains("set local lock_timeout = '10s'")),
                () -> assertTrue(validationMigration.contains(
                        "validate constraint calendar_public_token_check_v13")),
                () -> assertFalse(validationMigration.contains("drop constraint")),
                () -> assertTrue(replacementMigration.contains("set local lock_timeout = '10s'")),
                () -> assertTrue(replacementMigration.contains(
                        "drop constraint if exists calendar_public_token_check")),
                () -> assertTrue(replacementMigration.contains(
                        "rename constraint calendar_public_token_check_v13 to calendar_public_token_check")),
                () -> assertFalse(replacementMigration.contains("validate constraint")));
    }

    @Test
    void publishedCanonicalTokenMigrationsRemainByteForByteImmutable() throws IOException {
        assertAll(
                () -> assertEquals(
                        -1_578_666_570,
                        migrationChecksum("V13__reapply_canonical_calendar_link_token_constraint.sql")),
                () -> assertEquals(
                        1_814_856_802,
                        migrationChecksum("V14__validate_canonical_calendar_link_token_constraint.sql")),
                () -> assertEquals(
                        1_636_628_730,
                        migrationChecksum("V15__replace_canonical_calendar_link_token_constraint.sql")));
    }

    @Test
    void descriptionConstraintsUseOneTablePerLowLockInstallationAndValidationPhase()
            throws IOException {
        String calendarInstallationMigration =
                migrationSql("V16__bound_calendar_description.sql");
        String eventInstallationMigration =
                migrationSql("V17__bound_calendar_event_description.sql");
        String calendarValidationMigration =
                migrationSql("V18__validate_calendar_description_length_constraint.sql");
        String eventValidationMigration =
                migrationSql("V19__validate_calendar_event_description_length_constraint.sql");

        assertAll(
                () -> assertTrue(calendarInstallationMigration.contains(
                        "set local lock_timeout = '10s'")),
                () -> assertTrue(calendarInstallationMigration.contains(
                        "set local statement_timeout = '5min'")),
                () -> assertTrue(calendarInstallationMigration.contains(
                        "calendar_description_maximum_length_check")),
                () -> assertFalse(calendarInstallationMigration.contains("alter table calendar_event")),
                () -> assertTrue(eventInstallationMigration.contains(
                        "set local lock_timeout = '10s'")),
                () -> assertTrue(eventInstallationMigration.contains(
                        "set local statement_timeout = '5min'")),
                () -> assertTrue(eventInstallationMigration.contains(
                        "calendar_event_description_maximum_length_check")),
                () -> assertFalse(eventInstallationMigration.contains("alter table calendar\n")),
                () -> assertTrue(calendarInstallationMigration.contains("char_length(description)")),
                () -> assertTrue(eventInstallationMigration.contains("regexp_count(description")),
                () -> assertTrue(calendarInstallationMigration.contains("<= 4000")),
                () -> assertTrue(eventInstallationMigration.contains("not valid")),
                () -> assertFalse(calendarInstallationMigration.contains("validate constraint")),
                () -> assertTrue(calendarValidationMigration.contains(
                        "set local lock_timeout = '10s'")),
                () -> assertTrue(calendarValidationMigration.contains(
                        "set local statement_timeout = '5min'")),
                () -> assertTrue(calendarValidationMigration.contains(
                        "validate constraint calendar_description_maximum_length_check")),
                () -> assertFalse(calendarValidationMigration.contains("alter table calendar_event")),
                () -> assertTrue(eventValidationMigration.contains(
                        "set local lock_timeout = '10s'")),
                () -> assertTrue(eventValidationMigration.contains(
                        "set local statement_timeout = '5min'")),
                () -> assertTrue(eventValidationMigration.contains(
                        "validate constraint calendar_event_description_maximum_length_check")),
                () -> assertFalse(eventValidationMigration.contains("alter table calendar\n")));
    }

    @Test
    void queryIndexesUseRecoverableOneIndexConcurrentMigrations() throws IOException {
        List<IndexMigration> indexMigrations = List.of(
                IndexMigration.create(
                        "V20__create_event_cursor_index.sql",
                        "idx_calendar_event_calendar_start_id",
                        "on calendar_event(calendar_id, start_at, id)"),
                IndexMigration.create(
                        "V21__create_all_day_event_index.sql",
                        "idx_calendar_event_all_day_calendar",
                        "where all_day = true"),
                IndexMigration.create(
                        "V22__create_invitation_creator_history_index.sql",
                        "idx_app_invitation_created_by_user_created_at_id",
                        "on app_invitation(created_by_user_id, created_at desc, id desc)"),
                IndexMigration.create(
                        "V23__create_invitation_calendar_history_index.sql",
                        "idx_app_invitation_calendar_created_at_id",
                        "where calendar_id is not null"),
                IndexMigration.create(
                        "V24__create_registration_invitation_capacity_index.sql",
                        "idx_app_invitation_registration_creator_expires",
                        "and role_name is null"),
                IndexMigration.create(
                        "V25__create_editor_invitation_capacity_index.sql",
                        "idx_app_invitation_editor_calendar_expires",
                        "and accepted_at is null"),
                IndexMigration.drop(
                        "V26__drop_replaced_event_start_index.sql",
                        "idx_calendar_event_calendar_start"),
                IndexMigration.drop(
                        "V27__drop_replaced_invitation_creator_index.sql",
                        "idx_app_invitation_created_by_user_id"),
                IndexMigration.drop(
                        "V28__drop_replaced_invitation_calendar_index.sql",
                        "idx_app_invitation_calendar_id"));

        for (IndexMigration indexMigration : indexMigrations) {
            String migration = migrationSql(indexMigration.fileName());
            assertAll(
                    indexMigration.fileName(),
                    () -> assertEquals(
                            "executeintransaction=false",
                            migrationConfiguration(indexMigration.fileName() + ".conf")),
                    () -> assertTrue(migration.contains("set lock_timeout = '10s'")),
                    () -> assertTrue(migration.contains("set statement_timeout = '5min'")),
                    () -> assertFalse(migration.contains("set local")),
                    () -> assertEquals(1, occurrences(migration, "drop index concurrently if exists ")),
                    () -> assertTrue(migration.contains(
                            "drop index concurrently if exists " + indexMigration.indexName() + ";")),
                    () -> assertEquals(
                            indexMigration.createsIndex() ? 1 : 0,
                            occurrences(migration, "create index concurrently ")),
                    () -> assertTrue(migration.contains(indexMigration.expectedSql())));
        }

        assertAll(
                () -> assertFalse(Files.exists(DATABASE_RESOURCE_DIRECTORY.resolve(
                        "migration/V20__index_event_cursor_queries.sql"))),
                () -> assertFalse(Files.exists(DATABASE_RESOURCE_DIRECTORY.resolve(
                        "migration/V21__index_invitation_history_and_capacity_queries.sql"))));
    }

    private static String migrationSql(String fileName) throws IOException {
        return Files.readString(DATABASE_RESOURCE_DIRECTORY.resolve("migration").resolve(fileName))
                .toLowerCase(Locale.ROOT);
    }

    private static String migrationConfiguration(String fileName) throws IOException {
        return Files.readString(DATABASE_RESOURCE_DIRECTORY.resolve("migration").resolve(fileName))
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static int occurrences(String value, String expectedSubstring) {
        int occurrenceCount = 0;
        int searchFromIndex = 0;
        while (true) {
            int occurrenceIndex = value.indexOf(expectedSubstring, searchFromIndex);
            if (occurrenceIndex < 0) {
                return occurrenceCount;
            }
            occurrenceCount++;
            searchFromIndex = occurrenceIndex + expectedSubstring.length();
        }
    }

    private static int migrationChecksum(String fileName) throws IOException {
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(
                DATABASE_RESOURCE_DIRECTORY.resolve("migration").resolve(fileName))) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return (int) checksum.getValue();
    }

    private record IndexMigration(
            String fileName,
            String indexName,
            boolean createsIndex,
            String expectedSql) {
        private static IndexMigration create(
                String fileName,
                String indexName,
                String expectedSql) {
            return new IndexMigration(fileName, indexName, true, expectedSql);
        }

        private static IndexMigration drop(String fileName, String indexName) {
            return new IndexMigration(fileName, indexName, false, "drop index concurrently");
        }
    }
}
