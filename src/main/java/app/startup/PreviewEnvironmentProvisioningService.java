package app.startup;

import app.calendar.CalendarService;
import app.security.PasswordService;
import app.user.ApplicationUser;
import app.user.RegistrationBootstrapState;
import app.user.RegistrationService;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Stateless
public class PreviewEnvironmentProvisioningService {
    private static final String PREVIEW_DISPLAY_NAME = "Preview user";
    private static final String PREVIEW_CALENDAR_NAME = "Preview calendar";

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    @Inject
    private CalendarService calendarService;

    @Inject
    private PasswordService passwordService;

    @Inject
    private RegistrationService registrationService;

    public void ensureProvisioned(PreviewEnvironmentConfiguration configuration) {
        List<ApplicationUser> configuredAccounts = entityManager
                .createQuery(
                        "select applicationUser from ApplicationUser applicationUser "
                                + "where applicationUser.username = :username",
                        ApplicationUser.class)
                .setParameter("username", configuration.username())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList();
        if (!configuredAccounts.isEmpty()) {
            synchronizeExistingAccount(configuredAccounts.getFirst(), configuration);
            return;
        }

        long existingUserCount = entityManager
                .createQuery("select count(applicationUser) from ApplicationUser applicationUser", Long.class)
                .getSingleResult();
        if (existingUserCount != 0) {
            throw new IllegalStateException(
                    "Preview account provisioning refuses to modify a nonempty database without the configured preview account.");
        }

        try {
            registrationService.register(
                    configuration.bootstrapInvitationToken(),
                    configuration.username(),
                    PREVIEW_DISPLAY_NAME,
                    configuration.password(),
                    configuration.password(),
                    PREVIEW_CALENDAR_NAME);
        } catch (ValidationException exception) {
            throw new IllegalStateException(
                    "The configured preview account could not be created in the fresh preview database.",
                    exception);
        }
    }

    private void synchronizeExistingAccount(
            ApplicationUser account, PreviewEnvironmentConfiguration configuration) {
        if (!passwordService.verifyPassword(configuration.password(), account.getPasswordHash())) {
            account.setPasswordHash(passwordService.hashPassword(
                    configuration.username(), configuration.password()));
            account.setPasswordVersion(Math.incrementExact(account.getPasswordVersion()));
        }

        RegistrationBootstrapState bootstrapState = entityManager.find(
                RegistrationBootstrapState.class,
                RegistrationBootstrapState.SINGLETON_ID,
                LockModeType.PESSIMISTIC_WRITE);
        if (bootstrapState == null) {
            throw new IllegalStateException("Registration bootstrap state is missing.");
        }
        if (bootstrapState.getConsumedAt() == null) {
            bootstrapState.setConsumedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }

        long membershipCount = entityManager
                .createQuery(
                        "select count(calendarMembership) from CalendarMembership calendarMembership "
                                + "where calendarMembership.user.id = :userId",
                        Long.class)
                .setParameter("userId", account.getId())
                .getSingleResult();
        if (membershipCount == 0) {
            calendarService.createCalendar(account, PREVIEW_CALENDAR_NAME);
        }
    }
}
