package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

final class PostgreSqlApplicationContractEndToEndIT {
    private static final String POSTGRESQL_HOST_ENVIRONMENT_VARIABLE = "PGHOST";
    private static final String POSTGRESQL_PORT_ENVIRONMENT_VARIABLE = "PGPORT";
    private static final String POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE = "PGDATABASE";
    private static final String POSTGRESQL_USER_ENVIRONMENT_VARIABLE = "PGUSER";
    private static final String POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE = "PGPASSWORD";
    private static final Duration DATABASE_LOCK_OBSERVATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DATABASE_LOCK_OBSERVATION_INTERVAL = Duration.ofMillis(25);
    private static final Duration CONCURRENT_INSERT_COMPLETION_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void flywayHistoryMatchesTheValidatedApplicationMigrationChain() throws SQLException {
        DatabaseConfiguration databaseConfiguration = databaseConfiguration();
        Flyway flyway = Flyway.configure()
                .dataSource(
                        databaseConfiguration.jdbcUrl(),
                        databaseConfiguration.username(),
                        databaseConfiguration.password())
                .locations("classpath:db/migration")
                .load();

        flyway.validate();

        List<Integer> installedRanks = new ArrayList<>();
        List<String> installedVersions = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select installed_rank, version, success "
                                + "from flyway_schema_history order by installed_rank");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                installedRanks.add(resultSet.getInt("installed_rank"));
                String installedVersion = resultSet.getString("version");
                installedVersions.add(installedVersion);
                assertTrue(
                        resultSet.getBoolean("success"),
                        () -> "Flyway migration " + installedVersion + " must be successful.");
            }
        }

        assertEquals(
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
                installedRanks,
                "The isolated database must contain one ordered history row for every migration.");
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
                installedVersions,
                "No versioned SQL or Java migration may be missing from the deployed schema.");
    }

    @Test
    void domainCheckConstraintsArePresentValidatedAndEnforced() throws SQLException {
        Set<String> expectedConstraintNames = Set.of(
                "app_invitation_invite_token_check",
                "app_invitation_maximum_lifetime_check",
                "app_invitation_scope_check",
                "app_registration_bootstrap_singleton_check",
                "app_user_password_version_check",
                "calendar_event_check",
                "calendar_member_role_name_check",
                "calendar_public_token_check");
        assertEquals(
                expectedConstraintNames,
                validatedCheckConstraintNames(expectedConstraintNames),
                "Every load-bearing domain check must exist and be validated.");
        assertEquals(
                List.of(),
                unvalidatedApplicationConstraints(),
                "The final migrated schema must not leave staged constraints unvalidated.");

        try (Connection connection = openDatabaseConnection()) {
            connection.setAutoCommit(false);
            long ownerUserId = insertUser(connection, "contract-owner-" + uniqueSuffix());
            long calendarId = insertCalendar(connection, ownerUserId, uniqueCalendarToken());

            assertCheckConstraintRejects(
                    connection,
                    "calendar_public_token_check",
                    "update calendar set public_token = ? where id = ?",
                    "AAAAAAAAAAB",
                    calendarId);
            assertCheckConstraintRejects(
                    connection,
                    "calendar_member_role_name_check",
                    "insert into calendar_member (calendar_id, user_id, role_name) values (?, ?, ?)",
                    calendarId,
                    ownerUserId,
                    "VIEWER");
            assertCheckConstraintRejects(
                    connection,
                    "app_invitation_scope_check",
                    "insert into app_invitation "
                            + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at) "
                            + "values (?, ?, ?, ?, now() + interval '7 days')",
                    uniqueInvitationToken(),
                    calendarId,
                    "ADMIN",
                    ownerUserId);
            assertCheckConstraintRejects(
                    connection,
                    "app_invitation_scope_check",
                    "insert into app_invitation "
                            + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at) "
                            + "values (?, null, ?, ?, now() + interval '7 days')",
                    uniqueInvitationToken(),
                    "EDITOR",
                    ownerUserId);
            assertCheckConstraintRejects(
                    connection,
                    "app_invitation_invite_token_check",
                    "insert into app_invitation "
                            + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at) "
                            + "values (?, null, null, ?, now() + interval '7 days')",
                    "short-token",
                    ownerUserId);
            assertCheckConstraintRejects(
                    connection,
                    "app_invitation_maximum_lifetime_check",
                    "insert into app_invitation "
                            + "(invite_token, calendar_id, role_name, created_by_user_id, expires_at, created_at) "
                            + "values (?, null, null, ?, now() + interval '8 days', now())",
                    uniqueInvitationToken(),
                    ownerUserId);
            assertCheckConstraintRejects(
                    connection,
                    "calendar_event_check",
                    "insert into calendar_event "
                            + "(calendar_id, title, start_at, end_at, created_by_user_id) "
                            + "values (?, ?, timestamptz '2026-07-17 10:00:00+00', "
                            + "timestamptz '2026-07-17 10:00:00+00', ?)",
                    calendarId,
                    "Invalid zero-length event",
                    ownerUserId);
            assertCheckConstraintRejects(
                    connection,
                    "app_user_password_version_check",
                    "update app_user set password_version = -1 where id = ?",
                    ownerUserId);
            assertCheckConstraintRejects(
                    connection,
                    "app_registration_bootstrap_singleton_check",
                    "insert into app_registration_bootstrap (singleton_id) values (2)");

            connection.rollback();
        }
    }

    @Test
    void criticalApplicationQueriesRetainValidReadyIndexes() throws SQLException {
        List<DatabaseIndex> databaseIndexes = databaseIndexes();

        assertIndex(databaseIndexes, "app_user", "username", true);
        assertIndex(databaseIndexes, "calendar", "public_token", true);
        assertIndex(databaseIndexes, "calendar_member", "calendar_id,user_id", true);
        assertIndex(databaseIndexes, "calendar_member", "user_id", false);
        assertIndex(databaseIndexes, "app_invitation", "invite_token", true);
        assertIndex(databaseIndexes, "app_invitation", "calendar_id", false);
        assertIndex(databaseIndexes, "app_invitation", "created_by_user_id", false);
        assertIndex(databaseIndexes, "calendar_event", "calendar_id,start_at", false);
        assertIndex(databaseIndexes, "calendar_event", "calendar_id,end_at", false);
    }

    @Test
    void concurrentTransactionsCannotCreateDuplicateCalendarMemberships() throws Exception {
        DatabaseFixture databaseFixture = createCommittedDatabaseFixture();
        ExecutorService concurrentInsertExecutor = Executors.newSingleThreadExecutor();
        try (Connection firstTransaction = openDatabaseConnection()) {
            firstTransaction.setAutoCommit(false);
            insertCalendarMembership(
                    firstTransaction,
                    databaseFixture.calendarId(),
                    databaseFixture.candidateUserId(),
                    "ADMIN");

            String competingConnectionName = "membership-contract-" + uniqueSuffix();
            Future<ConcurrentInsertOutcome> competingInsert = concurrentInsertExecutor.submit(
                    () -> attemptCompetingMembershipInsert(databaseFixture, competingConnectionName));
            waitForDatabaseLock(competingConnectionName);

            firstTransaction.commit();
            ConcurrentInsertOutcome concurrentInsertOutcome = competingInsert.get(
                    CONCURRENT_INSERT_COMPLETION_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
            assertFalse(
                    concurrentInsertOutcome.committed(),
                    "Only one transaction may create a membership for a calendar and user.");
            assertEquals(
                    "23505",
                    concurrentInsertOutcome.sqlState(),
                    "The composite membership key must reject the transaction that loses the race.");
            assertEquals(
                    List.of("ADMIN"),
                    membershipRoles(databaseFixture.calendarId(), databaseFixture.candidateUserId()),
                    "The winning membership must remain intact without a duplicate or partial overwrite.");
        } finally {
            concurrentInsertExecutor.shutdownNow();
            assertTrue(
                    concurrentInsertExecutor.awaitTermination(5, TimeUnit.SECONDS),
                    "The concurrent database test executor must terminate cleanly.");
            deleteDatabaseFixture(databaseFixture);
        }
    }

    private Set<String> validatedCheckConstraintNames(Set<String> expectedConstraintNames) throws SQLException {
        Set<String> actualConstraintNames = new HashSet<>();
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select constraint_record.conname "
                                + "from pg_constraint constraint_record "
                                + "join pg_class table_record on table_record.oid = constraint_record.conrelid "
                                + "join pg_namespace namespace_record on namespace_record.oid = table_record.relnamespace "
                                + "where namespace_record.nspname = current_schema() "
                                + "and constraint_record.contype = 'c' "
                                + "and constraint_record.convalidated = true");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String constraintName = resultSet.getString("conname");
                if (expectedConstraintNames.contains(constraintName)) {
                    actualConstraintNames.add(constraintName);
                }
            }
        }
        return actualConstraintNames;
    }

    private List<String> unvalidatedApplicationConstraints() throws SQLException {
        List<String> unvalidatedConstraintNames = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select table_record.relname || '.' || constraint_record.conname as constraint_name "
                                + "from pg_constraint constraint_record "
                                + "join pg_class table_record on table_record.oid = constraint_record.conrelid "
                                + "join pg_namespace namespace_record on namespace_record.oid = table_record.relnamespace "
                                + "where namespace_record.nspname = current_schema() "
                                + "and table_record.relname in "
                                + "('app_user', 'calendar', 'calendar_member', 'app_invitation', "
                                + "'calendar_event', 'audit_log', 'app_registration_bootstrap') "
                                + "and constraint_record.convalidated = false "
                                + "order by table_record.relname, constraint_record.conname");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                unvalidatedConstraintNames.add(resultSet.getString("constraint_name"));
            }
        }
        return unvalidatedConstraintNames;
    }

    private void assertCheckConstraintRejects(
            Connection connection,
            String expectedConstraintName,
            String sql,
            Object... parameters) throws SQLException {
        Savepoint beforeInvalidStatement = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try {
                statement.executeUpdate();
                fail("Constraint " + expectedConstraintName + " accepted invalid application data.");
            } catch (SQLException exception) {
                assertEquals(
                        "23514",
                        exception.getSQLState(),
                        () -> "Constraint " + expectedConstraintName
                                + " must fail with PostgreSQL check-violation state.");
                assertTrue(
                        exception.getMessage().contains(expectedConstraintName),
                        () -> "Expected constraint " + expectedConstraintName
                                + " to reject the statement, but PostgreSQL reported: " + exception.getMessage());
            }
        } finally {
            connection.rollback(beforeInvalidStatement);
            connection.releaseSavepoint(beforeInvalidStatement);
        }
    }

    private List<DatabaseIndex> databaseIndexes() throws SQLException {
        List<DatabaseIndex> databaseIndexes = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select table_record.relname as table_name, "
                                + "index_record.relname as index_name, "
                                + "index_state.indisunique as unique_index, "
                                + "string_agg(table_attribute.attname, ',' order by index_column.ordinality) "
                                + "as indexed_columns "
                                + "from pg_index index_state "
                                + "join pg_class table_record on table_record.oid = index_state.indrelid "
                                + "join pg_class index_record on index_record.oid = index_state.indexrelid "
                                + "join pg_namespace namespace_record on namespace_record.oid = table_record.relnamespace "
                                + "cross join lateral unnest(index_state.indkey) with ordinality "
                                + "as index_column(attribute_number, ordinality) "
                                + "join pg_attribute table_attribute "
                                + "on table_attribute.attrelid = table_record.oid "
                                + "and table_attribute.attnum = index_column.attribute_number "
                                + "where namespace_record.nspname = current_schema() "
                                + "and index_state.indisvalid = true "
                                + "and index_state.indisready = true "
                                + "and index_column.ordinality <= index_state.indnkeyatts "
                                + "group by table_record.relname, index_record.relname, index_state.indisunique "
                                + "order by table_record.relname, index_record.relname");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                databaseIndexes.add(new DatabaseIndex(
                        resultSet.getString("table_name"),
                        resultSet.getString("index_name"),
                        resultSet.getBoolean("unique_index"),
                        resultSet.getString("indexed_columns")));
            }
        }
        return databaseIndexes;
    }

    private void assertIndex(
            List<DatabaseIndex> databaseIndexes,
            String tableName,
            String indexedColumns,
            boolean unique) {
        assertTrue(
                databaseIndexes.stream().anyMatch(databaseIndex -> databaseIndex.tableName().equals(tableName)
                        && databaseIndex.indexedColumns().equals(indexedColumns)
                        && databaseIndex.unique() == unique),
                () -> "Expected a valid, ready " + (unique ? "unique " : "") + "index on "
                        + tableName + " (" + indexedColumns + "). Available indexes: " + databaseIndexes);
    }

    private DatabaseFixture createCommittedDatabaseFixture() throws SQLException {
        try (Connection connection = openDatabaseConnection()) {
            connection.setAutoCommit(false);
            try {
                String uniqueSuffix = uniqueSuffix();
                long ownerUserId = insertUser(connection, "membership-owner-" + uniqueSuffix);
                long candidateUserId = insertUser(connection, "membership-candidate-" + uniqueSuffix);
                long calendarId = insertCalendar(connection, ownerUserId, uniqueCalendarToken());
                insertCalendarMembership(connection, calendarId, ownerUserId, "ADMIN");
                connection.commit();
                return new DatabaseFixture(ownerUserId, candidateUserId, calendarId);
            } catch (SQLException | RuntimeException | Error exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private long insertUser(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into app_user (username, display_name, password_hash) "
                        + "values (?, ?, ?) returning id")) {
            statement.setString(1, username);
            statement.setString(2, "PostgreSQL contract user");
            statement.setString(3, "test-only-password-hash");
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "The PostgreSQL contract user insert must return its identifier.");
                return resultSet.getLong("id");
            }
        }
    }

    private long insertCalendar(Connection connection, long ownerUserId, String publicToken) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into calendar (name, public_token, timezone, created_by_user_id) "
                        + "values (?, ?, ?, ?) returning id")) {
            statement.setString(1, "PostgreSQL contract calendar");
            statement.setString(2, publicToken);
            statement.setString(3, "Europe/Warsaw");
            statement.setLong(4, ownerUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "The PostgreSQL contract calendar insert must return its identifier.");
                return resultSet.getLong("id");
            }
        }
    }

    private void insertCalendarMembership(
            Connection connection,
            long calendarId,
            long userId,
            String roleName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into calendar_member (calendar_id, user_id, role_name) values (?, ?, ?)")) {
            statement.setLong(1, calendarId);
            statement.setLong(2, userId);
            statement.setString(3, roleName);
            assertEquals(1, statement.executeUpdate(), "Exactly one calendar membership must be inserted.");
        }
    }

    private ConcurrentInsertOutcome attemptCompetingMembershipInsert(
            DatabaseFixture databaseFixture,
            String connectionName) throws SQLException {
        try (Connection connection = openDatabaseConnection()) {
            connection.setAutoCommit(false);
            setConnectionName(connection, connectionName);
            try {
                insertCalendarMembership(
                        connection,
                        databaseFixture.calendarId(),
                        databaseFixture.candidateUserId(),
                        "EDITOR");
                connection.commit();
                return new ConcurrentInsertOutcome(true, null);
            } catch (SQLException exception) {
                connection.rollback();
                return new ConcurrentInsertOutcome(false, exception.getSQLState());
            }
        }
    }

    private void setConnectionName(Connection connection, String connectionName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('application_name', ?, false)")) {
            statement.setString(1, connectionName);
            statement.executeQuery().close();
        }
    }

    private void waitForDatabaseLock(String connectionName) throws Exception {
        long deadlineNanos = System.nanoTime() + DATABASE_LOCK_OBSERVATION_TIMEOUT.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try (Connection connection = openDatabaseConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "select count(*) from pg_stat_activity "
                                    + "where datname = current_database() "
                                    + "and application_name = ? "
                                    + "and state = 'active' "
                                    + "and wait_event_type = 'Lock'")) {
                statement.setString(1, connectionName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "The PostgreSQL lock observation query must return one row.");
                    if (resultSet.getLong(1) == 1L) {
                        return;
                    }
                }
            }
            Thread.sleep(DATABASE_LOCK_OBSERVATION_INTERVAL.toMillis());
        }
        fail("The competing membership transaction did not block on the composite membership key within "
                + DATABASE_LOCK_OBSERVATION_TIMEOUT.toSeconds() + " seconds.");
    }

    private List<String> membershipRoles(long calendarId, long userId) throws SQLException {
        List<String> membershipRoles = new ArrayList<>();
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select role_name from calendar_member "
                                + "where calendar_id = ? and user_id = ? order by role_name")) {
            statement.setLong(1, calendarId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    membershipRoles.add(resultSet.getString("role_name"));
                }
            }
        }
        return membershipRoles;
    }

    private void deleteDatabaseFixture(DatabaseFixture databaseFixture) throws SQLException {
        try (Connection connection = openDatabaseConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteCalendar = connection.prepareStatement(
                            "delete from calendar where id = ?");
                    PreparedStatement deleteUsers = connection.prepareStatement(
                            "delete from app_user where id in (?, ?)")) {
                deleteCalendar.setLong(1, databaseFixture.calendarId());
                assertEquals(1, deleteCalendar.executeUpdate(), "The contract calendar fixture must be deleted.");
                deleteUsers.setLong(1, databaseFixture.ownerUserId());
                deleteUsers.setLong(2, databaseFixture.candidateUserId());
                assertEquals(2, deleteUsers.executeUpdate(), "Both contract user fixtures must be deleted.");
                connection.commit();
            } catch (SQLException | RuntimeException | Error exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void bindParameters(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int parameterIndex = 0; parameterIndex < parameters.length; parameterIndex++) {
            statement.setObject(parameterIndex + 1, parameters[parameterIndex]);
        }
    }

    private Connection openDatabaseConnection() throws SQLException {
        DatabaseConfiguration databaseConfiguration = databaseConfiguration();
        return DriverManager.getConnection(
                databaseConfiguration.jdbcUrl(),
                databaseConfiguration.username(),
                databaseConfiguration.password());
    }

    private DatabaseConfiguration databaseConfiguration() {
        String jdbcUrl = "jdbc:postgresql://"
                + environmentValueOrDefault(POSTGRESQL_HOST_ENVIRONMENT_VARIABLE, "localhost")
                + ":"
                + environmentValueOrDefault(POSTGRESQL_PORT_ENVIRONMENT_VARIABLE, "5432")
                + "/"
                + environmentValueOrDefault(POSTGRESQL_DATABASE_ENVIRONMENT_VARIABLE, "calendar");
        return new DatabaseConfiguration(
                jdbcUrl,
                environmentValueOrDefault(POSTGRESQL_USER_ENVIRONMENT_VARIABLE, "calendar"),
                environmentValueOrDefault(POSTGRESQL_PASSWORD_ENVIRONMENT_VARIABLE, "calendar"));
    }

    private String uniqueCalendarToken() {
        byte[] tokenBytes = ByteBuffer.allocate(Long.BYTES)
                .putLong(UUID.randomUUID().getMostSignificantBits())
                .array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private String uniqueInvitationToken() {
        return "database-contract-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String environmentValueOrDefault(String variableName, String defaultValue) {
        String value = System.getenv(variableName);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private record DatabaseConfiguration(String jdbcUrl, String username, String password) {
    }

    private record DatabaseIndex(
            String tableName,
            String indexName,
            boolean unique,
            String indexedColumns) {
    }

    private record DatabaseFixture(long ownerUserId, long candidateUserId, long calendarId) {
    }

    private record ConcurrentInsertOutcome(boolean committed, String sqlState) {
    }
}
