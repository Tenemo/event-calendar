package app.user;

import app.security.PasswordService;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

@Stateless
public class UserService {
    private static final int MAXIMUM_USERNAME_LENGTH = 80;
    private static final int MAXIMUM_DISPLAY_NAME_LENGTH = 160;

    @PersistenceContext(unitName = "calendarPU")
    private EntityManager entityManager;

    @Inject
    private PasswordService passwordService;

    public ApplicationUser createUser(String username, String displayName, String password) {
        String normalizedUsername = normalizeUsername(username);
        String normalizedDisplayName = normalizeDisplayName(displayName);

        if (normalizedUsername.isBlank()) {
            throw new ValidationException("Username is required.");
        }
        if (normalizedDisplayName.isBlank()) {
            throw new ValidationException("Display name is required.");
        }
        requireMaximumLength(normalizedUsername, MAXIMUM_USERNAME_LENGTH, "Username must be 80 characters or fewer.");
        requireMaximumLength(normalizedDisplayName, MAXIMUM_DISPLAY_NAME_LENGTH, "Display name must be 160 characters or fewer.");
        if (findByUsername(normalizedUsername).isPresent()) {
            throw new ValidationException("Username is already registered.");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ApplicationUser user = new ApplicationUser();
        user.setUsername(normalizedUsername);
        user.setDisplayName(normalizedDisplayName);
        user.setPasswordHash(passwordService.hashPassword(normalizedUsername, password));
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            entityManager.persist(user);
            entityManager.flush();
        } catch (PersistenceException exception) {
            if (isUniqueConstraintViolation(exception)) {
                throw new ValidationException("Username is already registered.");
            }
            throw exception;
        }
        return user;
    }

    public Optional<ApplicationUser> findByUsername(String username) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername.isBlank()) {
            return Optional.empty();
        }

        try {
            ApplicationUser user = entityManager
                    .createQuery("select applicationUser from ApplicationUser applicationUser where applicationUser.username = :username", ApplicationUser.class)
                    .setParameter("username", normalizedUsername)
                    .getSingleResult();
            return Optional.of(user);
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    public Optional<ApplicationUser> findActiveByUsername(String username) {
        return findByUsername(username).filter(ApplicationUser::isActive);
    }

    public ApplicationUser requireActiveUser(Long userId) {
        ApplicationUser user = entityManager.find(ApplicationUser.class, userId);
        if (user == null || !user.isActive()) {
            throw new NotFoundException("User was not found.");
        }
        return user;
    }

    public String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return "";
        }
        return displayName.trim();
    }

    private void requireMaximumLength(String value, int maximumLength, String message) {
        if (value.length() > maximumLength) {
            throw new ValidationException(message);
        }
    }

    private boolean isUniqueConstraintViolation(Throwable exception) {
        Throwable currentException = exception;
        while (currentException != null) {
            if (currentException instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            Throwable nextException = currentException.getCause();
            if (nextException == currentException) {
                return false;
            }
            currentException = nextException;
        }
        return false;
    }
}
