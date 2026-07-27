package app.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

final class DatabaseMigrationVersionLedgerTest {
    private static final Path SQL_MIGRATION_DIRECTORY =
            Path.of("src", "main", "resources", "db", "migration");
    private static final Path JAVA_MIGRATION_DIRECTORY =
            Path.of("src", "main", "java", "db", "migration");
    private static final List<Path> MIGRATION_DIRECTORIES = List.of(
            SQL_MIGRATION_DIRECTORY,
            JAVA_MIGRATION_DIRECTORY);
    private static final Pattern MIGRATION_FILE_NAME = Pattern.compile(
            "^V(?<version>[1-9][0-9]*)__"
                    + "(?<description>[a-z0-9]+(?:_[a-z0-9]+)*)\\.(?<extension>sql|java)$");
    private static final Pattern CALENDAR_TOOL_VERSION = Pattern.compile(
            "EXPECTED_FLYWAY_VERSION\\s*=\\s*\"(?<version>\\d+)\"");
    private static final Pattern README_VERSION = Pattern.compile(
            "current schema is migration version (?<version>\\d+)\\.");
    private static final Map<String, String> IMMUTABLE_JAVA_MIGRATION_HELPER_SOURCE_DIGESTS = Map.of(
            "CalendarTimeZoneAudit.java",
            "4f9d7f8bcc8198ae92ea775db830784c4fa19c66c0d43ec3d16cac9048bc2146");
    private static final String NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION =
            "executeInTransaction=false\n";
    private static final Map<String, String> IMMUTABLE_SQL_MIGRATION_CONFIGURATION_CONTENTS = Map.of(
            "V20__create_event_cursor_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V21__create_all_day_event_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V22__create_invitation_creator_history_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V23__create_invitation_calendar_history_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V24__create_registration_invitation_capacity_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V25__create_editor_invitation_capacity_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V26__drop_replaced_event_start_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V27__drop_replaced_invitation_creator_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION,
            "V28__drop_replaced_invitation_calendar_index.sql.conf",
            NON_TRANSACTIONAL_SQL_MIGRATION_CONFIGURATION);
    private static final List<MigrationIdentity> IMMUTABLE_COMMITTED_MIGRATION_LEDGER = List.of(
            sql(1, "initial schema", "V1__initial_schema.sql", 1_097_011_125),
            sql(2, "invitation only registration", "V2__invitation_only_registration.sql", 1_388_351_245),
            sql(3, "unified app invitations", "V3__unified_app_invitations.sql", 1_276_001_534),
            sql(4, "editor only calendar invitations", "V4__editor_only_calendar_invitations.sql", 1_312_206_035),
            sql(5, "normalize all day event boundaries", "V5__normalize_all_day_event_boundaries.sql", 163_583_964),
            sql(6, "secure invitation admission", "V6__secure_invitation_admission.sql", -1_841_826_945),
            javaMigration(
                    7,
                    "normalize all day events for java time zones",
                    "db.migration.V7__normalize_all_day_events_for_java_time_zones",
                    "3a7c2491bb60e9ffeb2f088ee32e331453c704c15bfbcda789d73fa8b37ffb82"),
            sql(8, "remove viewer calendar role", "V8__remove_viewer_calendar_role.sql", -571_980_825),
            sql(9, "password change session revocation", "V9__password_change_session_revocation.sql", 1_725_179_370),
            javaMigration(
                    10,
                    "shorten calendar public tokens",
                    "db.migration.V10__shorten_calendar_public_tokens",
                    "68dd4c6c165f15dea2a3e980264435a6f9ec86ad3bbb6cc63cbb6feb6ada097f"),
            sql(11, "cap invitation lifetime", "V11__cap_invitation_lifetime.sql", -1_979_371_882),
            javaMigration(
                    12,
                    "validate calendar time zones",
                    "db.migration.V12__validate_calendar_time_zones",
                    "06524c5d9e0f000f52b6fdfe2a3e6dd7ee09bb76b450298c799e3cedd8972f29"),
            sql(
                    13,
                    "reapply canonical calendar link token constraint",
                    "V13__reapply_canonical_calendar_link_token_constraint.sql",
                    -1_578_666_570),
            sql(
                    14,
                    "validate canonical calendar link token constraint",
                    "V14__validate_canonical_calendar_link_token_constraint.sql",
                    1_814_856_802),
            sql(
                    15,
                    "replace canonical calendar link token constraint",
                    "V15__replace_canonical_calendar_link_token_constraint.sql",
                    1_636_628_730),
            sql(16, "bound calendar description", "V16__bound_calendar_description.sql", 1_575_427_898),
            sql(
                    17,
                    "bound calendar event description",
                    "V17__bound_calendar_event_description.sql",
                    -1_666_785_734),
            sql(
                    18,
                    "validate calendar description length constraint",
                    "V18__validate_calendar_description_length_constraint.sql",
                    2_021_888_041),
            sql(
                    19,
                    "validate calendar event description length constraint",
                    "V19__validate_calendar_event_description_length_constraint.sql",
                    -1_134_465_134),
            sql(20, "create event cursor index", "V20__create_event_cursor_index.sql", -1_279_983_988),
            sql(21, "create all day event index", "V21__create_all_day_event_index.sql", 1_332_083_674),
            sql(
                    22,
                    "create invitation creator history index",
                    "V22__create_invitation_creator_history_index.sql",
                    -577_394_088),
            sql(
                    23,
                    "create invitation calendar history index",
                    "V23__create_invitation_calendar_history_index.sql",
                    -196_455_423),
            sql(
                    24,
                    "create registration invitation capacity index",
                    "V24__create_registration_invitation_capacity_index.sql",
                    449_031_106),
            sql(
                    25,
                    "create editor invitation capacity index",
                    "V25__create_editor_invitation_capacity_index.sql",
                    -1_784_259_505),
            sql(
                    26,
                    "drop replaced event start index",
                    "V26__drop_replaced_event_start_index.sql",
                    336_663_871),
            sql(
                    27,
                    "drop replaced invitation creator index",
                    "V27__drop_replaced_invitation_creator_index.sql",
                    -336_845_991),
            sql(
                    28,
                    "drop replaced invitation calendar index",
                    "V28__drop_replaced_invitation_calendar_index.sql",
                    -1_460_396_227));

    @Test
    void committedMigrationVersionsRemainContiguousAndMatchEveryOperationalLedger()
            throws IOException {
        Map<Integer, MigrationIdentity> committedMigrationsByVersion = committedMigrationsByVersion();
        assertEquals(
                IMMUTABLE_COMMITTED_MIGRATION_LEDGER,
                List.copyOf(committedMigrationsByVersion.values()),
                "Published Flyway migration identities must remain immutable.");
        Set<Integer> committedMigrationVersions = committedMigrationsByVersion.keySet();
        assertFalse(committedMigrationVersions.isEmpty(), "At least one migration must exist.");

        int highestCommittedMigrationVersion = committedMigrationVersions.stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElseThrow();
        Set<Integer> expectedContiguousVersions = IntStream
                .rangeClosed(1, highestCommittedMigrationVersion)
                .boxed()
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(
                expectedContiguousVersions,
                committedMigrationVersions,
                "Committed Flyway migration versions must remain contiguous.");

        int calendarToolVersion = singleVersion(
                Path.of("scripts", "CalendarToolPostgreSql.java"),
                CALENDAR_TOOL_VERSION,
                "CalendarTool expected Flyway version");
        int readmeVersion = singleVersion(
                Path.of("README.md"),
                README_VERSION,
                "README current schema version");

        assertEquals(
                highestCommittedMigrationVersion,
                calendarToolVersion,
                "CalendarTool must expect the highest committed Flyway migration.");
        assertEquals(
                highestCommittedMigrationVersion,
                readmeVersion,
                "README must report the highest committed Flyway migration.");
    }

    private static Map<Integer, MigrationIdentity> committedMigrationsByVersion() throws IOException {
        TreeMap<Integer, MigrationIdentity> migrationsByVersion = new TreeMap<>();
        TreeMap<String, String> javaMigrationHelperSourceDigests = new TreeMap<>();
        TreeMap<String, String> sqlMigrationConfigurationContents = new TreeMap<>();
        for (Path migrationDirectory : MIGRATION_DIRECTORIES) {
            try (var migrationFiles = Files.walk(migrationDirectory)) {
                for (Path migrationFile : migrationFiles
                        .filter(Files::isRegularFile)
                        .toList()) {
                    String fileName = migrationFile.getFileName().toString();
                    if (fileName.endsWith(".sql.conf")) {
                        assertEquals(
                                SQL_MIGRATION_DIRECTORY,
                                migrationDirectory,
                                () -> "Flyway SQL migration configurations must remain in the resource directory: "
                                        + migrationFile + ".");
                        assertEquals(
                                1,
                                migrationDirectory.relativize(migrationFile).getNameCount(),
                                () -> "Flyway SQL migration configurations must not be nested: "
                                        + migrationFile + ".");
                        assertTrue(
                                IMMUTABLE_SQL_MIGRATION_CONFIGURATION_CONTENTS.containsKey(fileName),
                                () -> "Unsupported Flyway SQL migration configuration: "
                                        + migrationFile + ".");
                        sqlMigrationConfigurationContents.put(
                                fileName,
                                normalizedTextFileContents(migrationFile));
                        continue;
                    }
                    Matcher matcher = MIGRATION_FILE_NAME.matcher(fileName);
                    if (!matcher.matches()) {
                        if (migrationDirectory.equals(JAVA_MIGRATION_DIRECTORY)
                                && IMMUTABLE_JAVA_MIGRATION_HELPER_SOURCE_DIGESTS.containsKey(fileName)) {
                            assertEquals(
                                    1,
                                    migrationDirectory.relativize(migrationFile).getNameCount(),
                                    () -> "Flyway Java migration helpers must not be nested: "
                                            + migrationFile + ".");
                            javaMigrationHelperSourceDigests.put(
                                    fileName,
                                    normalizedSourceDigest(migrationFile));
                            continue;
                        }
                        assertFalse(
                                fileName.endsWith(".sql") || fileName.endsWith(".java"),
                                () -> "Unsupported Flyway migration resource: " + migrationFile + ".");
                        continue;
                    }
                    assertEquals(
                            1,
                            migrationDirectory.relativize(migrationFile).getNameCount(),
                            () -> "Flyway migration files must not be nested: " + migrationFile + ".");
                    int migrationVersion = Integer.parseInt(matcher.group("version"));
                    String description = matcher.group("description").replace('_', ' ');
                    String extension = matcher.group("extension");
                    assertEquals(
                            migrationDirectory.equals(SQL_MIGRATION_DIRECTORY) ? "sql" : "java",
                            extension,
                            () -> "Flyway migrations must use the source type configured for their directory: "
                                    + migrationFile + ".");
                    MigrationIdentity migrationIdentity = extension.equals("sql")
                            ? sql(
                                    migrationVersion,
                                    description,
                                    migrationFile.getFileName().toString(),
                                    flywaySqlChecksum(migrationFile))
                            : javaMigration(
                                    migrationVersion,
                                    description,
                                    "db.migration." + migrationFile.getFileName().toString()
                                            .substring(0, migrationFile.getFileName().toString().length()
                                                    - ".java".length()),
                                    normalizedSourceDigest(migrationFile));
                    MigrationIdentity previousMigration = migrationsByVersion.putIfAbsent(
                            migrationVersion,
                            migrationIdentity);
                    assertTrue(
                            previousMigration == null,
                            () -> "Flyway migration version " + migrationVersion
                                    + " is duplicated by " + previousMigration + " and " + migrationIdentity + ".");
                }
            }
        }
        assertEquals(
                IMMUTABLE_SQL_MIGRATION_CONFIGURATION_CONTENTS,
                sqlMigrationConfigurationContents,
                "Published Flyway SQL migration configurations must remain immutable.");
        Set<String> committedSqlMigrationScripts = migrationsByVersion.values().stream()
                .filter(migration -> migration.migrationType().equals("SQL"))
                .map(MigrationIdentity::script)
                .collect(Collectors.toSet());
        for (String configurationFileName : sqlMigrationConfigurationContents.keySet()) {
            assertTrue(
                    committedSqlMigrationScripts.contains(configurationFileName.substring(
                            0,
                            configurationFileName.length() - ".conf".length())),
                    () -> "Flyway SQL migration configuration does not have a matching migration: "
                            + configurationFileName + ".");
        }
        assertEquals(
                IMMUTABLE_JAVA_MIGRATION_HELPER_SOURCE_DIGESTS,
                javaMigrationHelperSourceDigests,
                "Published Flyway Java migration helper sources must remain immutable.");
        return migrationsByVersion;
    }

    private static String normalizedTextFileContents(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
    }

    private static MigrationIdentity sql(
            int version,
            String description,
            String script,
            int checksum) {
        return new MigrationIdentity(version, description, "SQL", script, checksum, null);
    }

    private static MigrationIdentity javaMigration(
            int version,
            String description,
            String script,
            String normalizedSourceDigest) {
        return new MigrationIdentity(
                version,
                description,
                "JDBC",
                script,
                null,
                normalizedSourceDigest);
    }

    private static int flywaySqlChecksum(Path migrationPath) throws IOException {
        CRC32 checksum = new CRC32();
        List<String> migrationLines = Files.readAllLines(migrationPath, StandardCharsets.UTF_8);
        for (int lineIndex = 0; lineIndex < migrationLines.size(); lineIndex++) {
            String migrationLine = migrationLines.get(lineIndex);
            if (lineIndex == 0 && migrationLine.startsWith("\uFEFF")) {
                migrationLine = migrationLine.substring(1);
            }
            checksum.update(migrationLine.getBytes(StandardCharsets.UTF_8));
        }
        return (int) checksum.getValue();
    }

    private static String normalizedSourceDigest(Path migrationPath) throws IOException {
        String normalizedSource = Files.readString(migrationPath, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedSource.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256.", exception);
        }
    }

    private static int singleVersion(
            Path ledgerPath,
            Pattern versionPattern,
            String ledgerDescription) throws IOException {
        Matcher matcher = versionPattern.matcher(Files.readString(ledgerPath));
        assertTrue(matcher.find(), ledgerDescription + " must be present.");
        int version = Integer.parseInt(matcher.group("version"));
        assertFalse(matcher.find(), ledgerDescription + " must have one authoritative value.");
        return version;
    }

    private record MigrationIdentity(
            int version,
            String description,
            String migrationType,
            String script,
            Integer checksum,
            String normalizedSourceDigest) {}
}
