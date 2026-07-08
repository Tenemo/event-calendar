package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.util.ValidationException;
import org.junit.jupiter.api.Test;

final class PasswordServiceTest {
    private final PasswordService passwordService = new PasswordService();

    @Test
    void hashesPasswordsWithRandomSaltAndVerifiesOnlyTheCorrectPassword() {
        String password = "correct horse battery staple";

        String firstHash = passwordService.hashPassword("piotr", password);
        String secondHash = passwordService.hashPassword("piotr", password);

        assertAll(
                () -> assertNotEquals(firstHash, secondHash, "Two hashes for the same password should use different salts."),
                () -> assertTrue(firstHash.startsWith("pbkdf2_sha256$600000$"), "New password hashes should use the current work factor."),
                () -> assertTrue(passwordService.verifyPassword(password, firstHash)),
                () -> assertTrue(passwordService.verifyPassword(password, secondHash)),
                () -> assertFalse(passwordService.verifyPassword("correct horse battery staple!", firstHash)),
                () -> assertFalse(passwordService.verifyPassword(null, firstHash)),
                () -> assertFalse(passwordService.verifyPassword(password, null)),
                () -> assertFalse(passwordService.verifyPassword(password, "not-a-supported-hash")));
    }

    @Test
    void verifiesExistingHashesWithOlderIterationCounts() {
        String oldHash = "pbkdf2_sha256$310000$Dw4NDAsKCQgHBgUEAwIBAA$WoLnVJrYQNsIvn9kjgoVwTKIGzSCUXgJfY7_Ypn6Fp0";

        assertAll(
                () -> assertTrue(passwordService.verifyPassword("correct horse battery staple", oldHash)),
                () -> assertFalse(passwordService.verifyPassword("correct horse battery staple!", oldHash)));
    }

    @Test
    void rejectsWeakOrSelfReferentialPasswordsBeforeHashing() {
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
        String hash = passwordService.hashPassword("piotr", "correct horse battery staple");

        assertFalse(passwordService.verifyPassword("p".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH + 1), hash));
    }
}
