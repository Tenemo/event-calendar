package app.user;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setField;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.TestPasswordServices.passwordService;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.security.PasswordService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.util.ValidationException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class UserServiceTest {
    private static final String VALID_PASSWORD = "Correct horse battery staple 1";

    @Test
    void rejectsBlankUsernameAndDisplayNameBeforePersisting() {
        UserService userService = new UserService();

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("   ", "Display name", VALID_PASSWORD)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("piotr", "   ", VALID_PASSWORD)));
    }

    @Test
    void rejectsValuesLongerThanTheSchemaAllowsBeforePersisting() {
        UserService userService = new UserService();

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("u".repeat(81), "Display name", VALID_PASSWORD)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("piotr", "D".repeat(161), VALID_PASSWORD)));
    }

    @Test
    void rejectsDuplicateUsernameCaseInsensitively() {
        EntityManagerStub entityManagerStub = entityManagerStub();
        ApplicationUser existingUser = new ApplicationUser();
        setEntityId(existingUser, 1L);
        existingUser.setUsername("piotr");
        entityManagerStub.singleResult("from ApplicationUser applicationUser", existingUser);

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService());

        assertThrows(
                ValidationException.class,
                () -> userService.createUser(" Piotr ", "Piotr", VALID_PASSWORD));
    }

    @Test
    void normalizesAndPersistsNewUsersWithAHashedPassword() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from ApplicationUser applicationUser");
        PasswordService passwordService = passwordService();

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService);

        ApplicationUser createdUser = userService.createUser(" Piotr ", " Piotr Tenemo ", VALID_PASSWORD);

        assertAll(
                () -> assertEquals("piotr", createdUser.getUsername()),
                () -> assertEquals("Piotr Tenemo", createdUser.getDisplayName()),
                () -> assertEquals(ZoneOffset.UTC, createdUser.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, createdUser.getUpdatedAt().getOffset()),
                () -> assertTrue(passwordService.verifyPassword(VALID_PASSWORD, createdUser.getPasswordHash())),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }

    @Test
    void reportsDatabaseUsernameRaceAsValidationFailure() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from ApplicationUser applicationUser")
                .failOnFlush(new PersistenceException(new SQLException("duplicate username", "23505")));

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService());

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> userService.createUser("piotr", "Piotr", VALID_PASSWORD));

        assertAll(
                () -> assertEquals("Username is already registered.", exception.getMessage()),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }
}
