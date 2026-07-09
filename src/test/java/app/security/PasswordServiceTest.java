package app.security;

import static app.testsupport.TestPasswordServices.passwordService;
import static org.junit.jupiter.api.Assertions.assertAll;
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
        String password = "correct horse battery staple";

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
                () -> assertFalse(passwordService.verifyPassword("correct horse battery staple!", firstHash)),
                () -> assertFalse(passwordService.verifyPassword(null, firstHash)),
                () -> assertFalse(passwordService.verifyPassword(password, null)),
                () -> assertFalse(passwordService.verifyPassword(password, "not-a-supported-hash")));
    }

    @Test
    void verifiesExistingHashesWithOlderIterationCounts() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String oldHash = "pbkdf2_sha256$310000$Dw4NDAsKCQgHBgUEAwIBAA$WoLnVJrYQNsIvn9kjgoVwTKIGzSCUXgJfY7_Ypn6Fp0";

        assertAll(
                () -> assertTrue(passwordService.verifyPassword("correct horse battery staple", oldHash)),
                () -> assertFalse(passwordService.verifyPassword("correct horse battery staple!", oldHash)),
                () -> assertEquals(0, passwordHash.verificationCount(), "Legacy hashes should not be sent to Jakarta Security."));
    }

    @Test
    void rejectsStoredHashesWithUnsupportedIterationCountsBeforeHashing() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);

        assertAll(
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        storedHashWithIterationCount(0))),
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        storedHashWithIterationCount(-1))),
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        storedHashWithIterationCount(99_999))),
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        storedHashWithIterationCount(600_001))),
                () -> assertFalse(passwordService.verifyPassword(
                        "correct horse battery staple",
                        storedHashWithIterationCount(Integer.MAX_VALUE))));
        assertEquals(0, passwordHash.verificationCount());
    }

    @Test
    void rejectsWeakOrSelfReferentialPasswordsBeforeHashing() {
        PasswordService passwordService = passwordService();

        assertAll(
                () -> assertThrows(ValidationException.class, () -> passwordService.hashPassword("piotr", "")),
                () -> assertThrows(ValidationException.class, () -> passwordService.hashPassword("piotr", "too-short")),
                () -> assertThrows(ValidationException.class, () -> passwordService.hashPassword("Piotr", "piotr")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> passwordService.hashPassword("piotr", "p".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH + 1))));
    }

    @Test
    void rejectsOversizedPasswordsDuringVerificationBeforeHashing() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String hash = passwordService.hashPassword("piotr", "correct horse battery staple");

        assertAll(
                () -> assertFalse(passwordService.verifyPassword("p".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH + 1), hash)),
                () -> assertEquals(0, passwordHash.verificationCount()));
    }

    @Test
    void routesJakartaSecurityHashesToJakartaPasswordHashOnly() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String hash = passwordService.hashPassword("piotr", "correct horse battery staple");

        assertAll(
                () -> assertTrue(passwordService.verifyPassword("correct horse battery staple", hash)),
                () -> assertEquals(1, passwordHash.verificationCount()),
                () -> assertEquals(hash, passwordHash.lastVerifiedHash()));
    }

    private String storedHashWithIterationCount(int iterationCount) {
        return "pbkdf2_sha256$"
                + iterationCount
                + "$Dw4NDAsKCQgHBgUEAwIBAA$WoLnVJrYQNsIvn9kjgoVwTKIGzSCUXgJfY7_Ypn6Fp0";
    }
}
