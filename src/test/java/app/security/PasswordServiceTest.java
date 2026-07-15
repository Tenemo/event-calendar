package app.security;

import static app.testsupport.TestPasswordServices.passwordService;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.testsupport.TestPasswordServices.RecordingPasswordHash;
import app.util.ValidationException;
import org.junit.jupiter.api.Test;

final class PasswordServiceTest {
    @Test
    void hashesPasswordsThroughJakartaSecurityAndVerifiesOnlyTheCorrectPassword() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String password = "Correct horse battery staple 1";

        String firstHash = passwordService.hashPassword("piotr", password);
        String secondHash = passwordService.hashPassword("piotr", password);

        assertAll(
                () -> assertNotEquals(firstHash, secondHash, "Two hashes for the same password should use different salts."),
                () -> assertTrue(
                        firstHash.startsWith("PBKDF2WithHmacSHA256:600000:"),
                        "New password hashes should use Jakarta Security's PBKDF2 format."),
                () -> assertEquals(1, passwordHash.initializationCount()),
                () -> assertEquals("PBKDF2WithHmacSHA256", passwordHash.initializedParameters().get("Pbkdf2PasswordHash.Algorithm")),
                () -> assertEquals("600000", passwordHash.initializedParameters().get("Pbkdf2PasswordHash.Iterations")),
                () -> assertEquals("32", passwordHash.initializedParameters().get("Pbkdf2PasswordHash.SaltSizeBytes")),
                () -> assertEquals("32", passwordHash.initializedParameters().get("Pbkdf2PasswordHash.KeySizeBytes")),
                () -> assertEquals(2, passwordHash.generationCount()),
                () -> assertTrue(passwordService.verifyPassword(password, firstHash)),
                () -> assertTrue(passwordService.verifyPassword(password, secondHash)),
                () -> assertFalse(passwordService.verifyPassword("Correct horse battery staple 1!", firstHash)),
                () -> assertFalse(passwordService.verifyPassword(null, firstHash)),
                () -> assertFalse(passwordService.verifyPassword(password, null)),
                () -> assertFalse(passwordService.verifyPassword(password, "not-a-supported-hash")));
    }

    @Test
    void rejectsNonJakartaSecurityHashesWithoutVerification() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);

        assertAll(
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        "PBKDF2WithHmacSHA256$600000$encoded-salt$encoded-hash")),
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        "PBKDF2WithHmacSHA256:600000:encoded-salt")),
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        "not-a-supported-hash")),
                () -> assertEquals(0, passwordHash.verificationCount()));
    }

    @Test
    void acceptsAnEightCharacterPasswordWithAnUppercaseLetterAndDigit() {
        PasswordService passwordService = passwordService();

        assertDoesNotThrow(() -> passwordService.validatePasswordPolicy("piotr", "A1234567"));
    }

    @Test
    void rejectsPasswordsThatViolateEachPolicyRequirementBeforeHashing() {
        PasswordService passwordService = passwordService();

        assertAll(
                () -> assertPasswordRejected(passwordService, "piotr", "", "Password is required."),
                () -> assertPasswordRejected(
                        passwordService, "piotr", "Abc1234", "Password must be at least 8 characters."),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        "abcdefgh1",
                        "Password must contain at least one uppercase letter."),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        "Abcdefgh",
                        "Password must contain at least one digit."),
                () -> assertPasswordRejected(
                        passwordService, "piotr2026", "Piotr2026", "Password must not match the username."),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        "A1" + "p".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH - 1),
                        "Password must be 512 characters or fewer."));
    }

    @Test
    void rejectsOversizedPasswordsDuringVerificationBeforeHashing() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String hash = passwordService.hashPassword("piotr", "Correct horse battery staple 1");

        assertAll(
                () -> assertFalse(passwordService.verifyPassword("p".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH + 1), hash)),
                () -> assertEquals(0, passwordHash.verificationCount()));
    }

    @Test
    void routesJakartaSecurityHashesToJakartaPasswordHashOnly() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String hash = passwordService.hashPassword("piotr", "Correct horse battery staple 1");

        assertAll(
                () -> assertTrue(passwordService.verifyPassword("Correct horse battery staple 1", hash)),
                () -> assertEquals(1, passwordHash.verificationCount()),
                () -> assertEquals(hash, passwordHash.lastVerifiedHash()));
    }

    private static void assertPasswordRejected(
            PasswordService passwordService,
            String username,
            String password,
            String expectedMessage) {
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> passwordService.hashPassword(username, password));
        assertEquals(expectedMessage, exception.getMessage());
    }
}
