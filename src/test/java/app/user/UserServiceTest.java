package app.user;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setField;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.TestPasswordServices.passwordService;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.security.PasswordService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.util.ValidationException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
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

    @Test
    void changesTheLockedUsersPasswordVersionAndWritesASecretFreeAuditRecord() {
        PasswordService passwordService = passwordService();
        ApplicationUser user = activeUserWithPassword(passwordService, VALID_PASSWORD);
        user.setPasswordVersion(6);
        String originalPasswordHash = user.getPasswordHash();
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where applicationUser.id = :userId", user);
        RecordingAuditService auditService = new RecordingAuditService();
        UserService userService = passwordChangeUserService(entityManagerStub, passwordService, auditService);
        String newPassword = "Changed calendar password 2026";

        userService.changePassword(user, VALID_PASSWORD, newPassword, newPassword);

        assertAll(
                () -> assertFalse(passwordService.verifyPassword(VALID_PASSWORD, user.getPasswordHash())),
                () -> assertTrue(passwordService.verifyPassword(newPassword, user.getPasswordHash())),
                () -> assertFalse(originalPasswordHash.equals(user.getPasswordHash())),
                () -> assertEquals(7, user.getPasswordVersion()),
                () -> assertEquals(ZoneOffset.UTC, user.getUpdatedAt().getOffset()),
                () -> assertEquals(1, entityManagerStub.flushCount()),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("where applicationUser.id = :userId"))),
                () -> assertEquals(1, auditService.recordCount),
                () -> assertSame(user, auditService.actingUser),
                () -> assertNull(auditService.calendar),
                () -> assertEquals("app_user", auditService.entityType),
                () -> assertEquals(user.getId(), auditService.entityId),
                () -> assertEquals("password_changed", auditService.action),
                () -> assertEquals("Password changed.", auditService.details),
                () -> assertFalse(auditService.details.contains(newPassword)));
    }

    @Test
    void rejectsWrongCurrentPasswordMismatchReuseAndPolicyFailuresWithoutMutation() {
        List<PasswordChangeFailureCase> failureCases = List.of(
                new PasswordChangeFailureCase(
                        "Wrong current password 1",
                        "Changed calendar password 2026",
                        "Changed calendar password 2026",
                        "Current password is incorrect."),
                new PasswordChangeFailureCase(
                        VALID_PASSWORD,
                        "Changed calendar password 2026",
                        "Different confirmation 2026",
                        "New password and confirmation must match."),
                new PasswordChangeFailureCase(
                        VALID_PASSWORD,
                        VALID_PASSWORD,
                        VALID_PASSWORD,
                        "New password must be different from the current password."),
                new PasswordChangeFailureCase(
                        VALID_PASSWORD,
                        "lowercase password 2026",
                        "lowercase password 2026",
                        "Password must contain at least one uppercase letter."));

        for (PasswordChangeFailureCase failureCase : failureCases) {
            PasswordService passwordService = passwordService();
            ApplicationUser user = activeUserWithPassword(passwordService, VALID_PASSWORD);
            user.setPasswordVersion(2);
            String originalPasswordHash = user.getPasswordHash();
            EntityManagerStub entityManagerStub = entityManagerStub()
                    .singleResult("where applicationUser.id = :userId", user);
            RecordingAuditService auditService = new RecordingAuditService();
            UserService userService = passwordChangeUserService(entityManagerStub, passwordService, auditService);

            ValidationException exception = assertThrows(
                    ValidationException.class,
                    () -> userService.changePassword(
                            user,
                            failureCase.currentPassword(),
                            failureCase.newPassword(),
                            failureCase.confirmation()));

            assertAll(
                    () -> assertEquals(failureCase.expectedMessage(), exception.getMessage()),
                    () -> assertEquals(originalPasswordHash, user.getPasswordHash()),
                    () -> assertEquals(2, user.getPasswordVersion()),
                    () -> assertEquals(0, entityManagerStub.flushCount()),
                    () -> assertEquals(0, auditService.recordCount));
        }
    }

    private ApplicationUser activeUserWithPassword(PasswordService passwordService, String password) {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, 41L);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setPasswordHash(passwordService.hashPassword(user.getUsername(), password));
        user.setActive(true);
        user.setCreatedAt(java.time.OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(java.time.OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        return user;
    }

    private UserService passwordChangeUserService(
            EntityManagerStub entityManagerStub,
            PasswordService passwordService,
            AuditService auditService) {
        UserService userService = new UserService();
        setField(userService, "entityManager", entityManagerStub.entityManager());
        setField(userService, "passwordService", passwordService);
        setField(userService, "auditService", auditService);
        return userService;
    }

    private record PasswordChangeFailureCase(
            String currentPassword,
            String newPassword,
            String confirmation,
            String expectedMessage) {
    }

    private static final class RecordingAuditService extends AuditService {
        private int recordCount;
        private ApplicationUser actingUser;
        private Calendar calendar;
        private String entityType;
        private Long entityId;
        private String action;
        private String details;

        @Override
        public void record(
                ApplicationUser actingUser,
                Calendar calendar,
                String entityType,
                Long entityId,
                String action,
                String details) {
            recordCount++;
            this.actingUser = actingUser;
            this.calendar = calendar;
            this.entityType = entityType;
            this.entityId = entityId;
            this.action = action;
            this.details = details;
        }
    }
}
