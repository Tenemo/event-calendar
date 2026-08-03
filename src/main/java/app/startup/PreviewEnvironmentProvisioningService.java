package app.startup;

import app.fixture.CanonicalCalendarFixture;
import app.fixture.CanonicalCalendarFixture.Installation;
import app.fixture.CanonicalCalendarFixture.Scope;
import app.security.PasswordService;
import app.security.TokenService;
import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

@Stateless
public class PreviewEnvironmentProvisioningService {
    private static final String LEGACY_PREVIEW_CALENDAR_NAME = "Preview calendar";

    @Resource(lookup = "jdbc/CalendarDataSource")
    private DataSource dataSource;

    @Inject
    private PasswordService passwordService;

    @Inject
    private TokenService tokenService;

    public void ensureProvisioned(PreviewEnvironmentConfiguration configuration) {
        try (Connection connection = dataSource.getConnection()) {
            acquireProvisioningLock(connection);
            lockRegistrationBootstrapState(connection);

            Optional<AccountState> ownerAccount = findAccount(connection, configuration.username());
            if (ownerAccount.isEmpty()) {
                if (countUsers(connection) != 0) {
                    throw new IllegalStateException(
                            "Preview provisioning refuses to add an account to a nonempty database.");
                }
                installFixture(connection, configuration, Optional.empty());
                return;
            }

            AccountState synchronizedOwner = synchronizeExistingOwner(
                    connection, ownerAccount.orElseThrow(), configuration);
            consumeRegistrationBootstrap(connection);
            long membershipCount = countMemberships(connection, synchronizedOwner.id());
            if (membershipCount == 0) {
                installFixture(connection, configuration, Optional.of(synchronizedOwner));
                return;
            }
            if (membershipCount == 1
                    && removeLegacyPreviewCalendar(connection, synchronizedOwner.id())) {
                installFixture(connection, configuration, Optional.of(synchronizedOwner));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Preview fixture provisioning could not access the database.", exception);
        }
    }

    private void installFixture(
            Connection connection,
            PreviewEnvironmentConfiguration configuration,
            Optional<AccountState> existingOwner) throws SQLException {
        Optional<AccountState> existingMaya = findFixtureCompanion(
                connection, configuration.mayaUsername(), "Maya Nowak");
        Optional<AccountState> existingTomasz = findFixtureCompanion(
                connection, configuration.tomaszUsername(), "Tomasz Zieliński");
        Set<String> calendarLinkTokens = generateCalendarLinkTokens();
        var calendarLinkTokenIterator = calendarLinkTokens.iterator();

        String ownerPasswordHash = existingOwner
                .map(AccountState::passwordHash)
                .orElseGet(() -> passwordService.hashPassword(
                        configuration.username(), configuration.password()));
        String mayaPasswordHash = existingMaya
                .map(AccountState::passwordHash)
                .orElseGet(() -> passwordService.hashPassword(
                        configuration.mayaUsername(), tokenService.generateInvitationToken()));
        String tomaszPasswordHash = existingTomasz
                .map(AccountState::passwordHash)
                .orElseGet(() -> passwordService.hashPassword(
                        configuration.tomaszUsername(), tokenService.generateInvitationToken()));

        Installation installation = new Installation(
                Scope.PREVIEW,
                configuration.username(),
                configuration.displayName(),
                ownerPasswordHash,
                existingOwner.isPresent(),
                configuration.mayaUsername(),
                mayaPasswordHash,
                existingMaya.isPresent(),
                configuration.tomaszUsername(),
                tomaszPasswordHash,
                existingTomasz.isPresent(),
                calendarLinkTokenIterator.next(),
                calendarLinkTokenIterator.next(),
                calendarLinkTokenIterator.next(),
                OffsetDateTime.now(ZoneOffset.UTC));
        CanonicalCalendarFixture.install(connection, installation);
        CanonicalCalendarFixture.readSummary(connection, installation)
                .verifyExpectedFixtureShape();
    }

    private AccountState synchronizeExistingOwner(
            Connection connection,
            AccountState owner,
            PreviewEnvironmentConfiguration configuration) throws SQLException {
        if (!configuration.displayName().equals(owner.displayName())) {
            throw new IllegalStateException(
                    "Preview provisioning refuses to take over an existing account with an unexpected display name.");
        }
        if (passwordService.verifyPassword(configuration.password(), owner.passwordHash())) {
            return owner;
        }

        String replacementPasswordHash =
                passwordService.hashPassword(configuration.username(), configuration.password());
        try (PreparedStatement statement = connection.prepareStatement(
                "update app_user set password_hash = ?, password_version = password_version + 1 where id = ?")) {
            statement.setString(1, replacementPasswordHash);
            statement.setLong(2, owner.id());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException(
                        "Preview account password synchronization updated no account.");
            }
        }
        return new AccountState(
                owner.id(), owner.username(), owner.displayName(), replacementPasswordHash);
    }

    private Optional<AccountState> findFixtureCompanion(
            Connection connection,
            String username,
            String expectedDisplayName) throws SQLException {
        Optional<AccountState> account = findAccount(connection, username);
        if (account.isPresent() && !expectedDisplayName.equals(account.orElseThrow().displayName())) {
            throw new IllegalStateException(
                    "Preview provisioning found a companion username owned by an unexpected account.");
        }
        return account;
    }

    private Optional<AccountState> findAccount(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, username, display_name, password_hash from app_user where username = ? for update")) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AccountState(
                        resultSet.getLong(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4)));
            }
        }
    }

    private void acquireProvisioningLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "select pg_advisory_xact_lock(hashtext('calendar.social preview fixture'))");
        }
    }

    private void lockRegistrationBootstrapState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "select singleton_id from registration_bootstrap where singleton_id = 1 for update")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Registration bootstrap state is missing.");
            }
        }
    }

    private long countUsers(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select count(*) from app_user")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Preview user count query returned no row.");
            }
            return resultSet.getLong(1);
        }
    }

    private long countMemberships(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from calendar_membership where user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Preview membership count query returned no row.");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private void consumeRegistrationBootstrap(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update registration_bootstrap set consumed_at = coalesce(consumed_at, ?) where singleton_id = 1")) {
            statement.setObject(1, OffsetDateTime.now(ZoneOffset.UTC));
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Registration bootstrap state could not be consumed.");
            }
        }
    }

    private boolean removeLegacyPreviewCalendar(Connection connection, long ownerId) throws SQLException {
        String query = """
                select
                    calendar.id,
                    calendar.name,
                    membership.role_name,
                    (select count(*) from calendar_membership where calendar_id = calendar.id),
                    (select count(*) from calendar_event where calendar_id = calendar.id),
                    (select count(*) from invitation
                        where calendar_id = calendar.id or created_by_user_id = ?)
                from calendar_membership membership
                join calendar on calendar.id = membership.calendar_id
                where membership.user_id = ?
                """;
        long calendarId;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setLong(1, ownerId);
            statement.setLong(2, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !LEGACY_PREVIEW_CALENDAR_NAME.equals(resultSet.getString(2))
                        || !"ADMIN".equals(resultSet.getString(3))
                        || resultSet.getLong(4) != 1
                        || resultSet.getLong(5) != 0
                        || resultSet.getLong(6) != 0) {
                    return false;
                }
                calendarId = resultSet.getLong(1);
                if (resultSet.next()) {
                    return false;
                }
            }
        }
        try (PreparedStatement statement =
                connection.prepareStatement("delete from calendar where id = ?")) {
            statement.setLong(1, calendarId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Legacy preview calendar could not be replaced.");
            }
        }
        return true;
    }

    private Set<String> generateCalendarLinkTokens() {
        Set<String> calendarLinkTokens = new LinkedHashSet<>();
        while (calendarLinkTokens.size() < 3) {
            calendarLinkTokens.add(tokenService.generateCalendarLinkToken());
        }
        return calendarLinkTokens;
    }

    private record AccountState(
            long id,
            String username,
            String displayName,
            String passwordHash) {}
}
