package app.user;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setField;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.security.PasswordService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.util.ValidationException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
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
    void rejectsDuplicateUsernameCaseInsensitively() {
        EntityManagerStub entityManagerStub = entityManagerStub();
        AppUser existingUser = new AppUser();
        setEntityId(existingUser, 1L);
        existingUser.setUsername("piotr");
        entityManagerStub.singleResult("from AppUser appUser", existingUser);

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", new PasswordService());

        assertThrows(
                ValidationException.class,
                () -> userService.createUser(" Piotr ", "Piotr", "correct horse battery staple"));
    }

    @Test
    void normalizesAndPersistsNewUsersWithAHashedPassword() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from AppUser appUser");
        PasswordService passwordService = new PasswordService();

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService);

        AppUser createdUser = userService.createUser(" Piotr ", " Piotr Tenemo ", "correct horse battery staple");

        assertAll(
                () -> assertEquals("piotr", createdUser.getUsername()),
                () -> assertEquals("Piotr Tenemo", createdUser.getDisplayName()),
                () -> assertTrue(passwordService.verifyPassword("correct horse battery staple", createdUser.getPasswordHash())),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }

    @Test
    void reportsDatabaseUsernameRaceAsValidationFailure() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResultNotFound("from AppUser appUser")
                .failOnFlush(new PersistenceException(new SQLException("duplicate username", "23505")));

        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", new PasswordService());

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> userService.createUser("piotr", "Piotr", "correct horse battery staple"));

        assertAll(
                () -> assertEquals("Username is already registered.", exception.getMessage()),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }
}
