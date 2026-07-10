package app.user;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setField;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.TestPasswordServices.passwordService;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Test
    void rejectsBlankUsernameAndDisplayNameBeforePersisting() {
        UserService userService = new UserService();

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("   ", "Display name", "correct horse battery staple")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("piotr", "   ", "correct horse battery staple")));
    }

    @Test
    void rejectsValuesLongerThanTheSchemaAllowsBeforePersisting() {
        UserService userService = new UserService();

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("u".repeat(81), "Display name", "correct horse battery staple")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> userService.createUser("piotr", "D".repeat(161), "correct horse battery staple")));
    }

    @Test
    void rejectsDuplicateUsernameCaseInsensitively() {
        EntityManagerStub entityManagerStub = entityManagerStub();
        AppUser existingUser = new AppUser();
        setEntityId(existingUser, 1L);
        existingUser.setUsername("piotr");
        entityManagerStub.singleResult("from AppUser appUser", existingUser);

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService());

        assertThrows(
                ValidationException.class,
                () -> userService.createUser(" Piotr ", "Piotr", "correct horse battery staple"));
    }

    @Test
    void normalizesAndPersistsNewUsersWithAHashedPassword() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from AppUser appUser");
        PasswordService passwordService = passwordService();

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService);

        AppUser createdUser = userService.createUser(" Piotr ", " Piotr Tenemo ", "correct horse battery staple");

        assertAll(
                () -> assertEquals("piotr", createdUser.getUsername()),
                () -> assertEquals("Piotr Tenemo", createdUser.getDisplayName()),
                () -> assertEquals(ZoneOffset.UTC, createdUser.getCreatedAt().getOffset()),
                () -> assertEquals(ZoneOffset.UTC, createdUser.getUpdatedAt().getOffset()),
                () -> assertTrue(passwordService.verifyPassword("correct horse battery staple", createdUser.getPasswordHash())),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }

    @Test
    void detectsWhetherAnyActiveUserExists() {
        UserService withUsers = new UserService();
        setField(withUsers, "entityManager", entityManagerStub()
                .singleResult("where appUser.active = true", 1L)
                .entityManager());
        UserService withoutUsers = new UserService();
        setField(withoutUsers, "entityManager", entityManagerStub()
                .singleResult("where appUser.active = true", 0L)
                .entityManager());

        assertAll(
                () -> assertTrue(withUsers.hasActiveUsers()),
                () -> assertFalse(withoutUsers.hasActiveUsers()));
    }

    @Test
    void reportsDatabaseUsernameRaceAsValidationFailure() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from AppUser appUser")
                .failOnFlush(new PersistenceException(new SQLException("duplicate username", "23505")));

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService());

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> userService.createUser("piotr", "Piotr", "correct horse battery staple"));

        assertAll(
                () -> assertEquals("Username is already registered.", exception.getMessage()),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }
}
