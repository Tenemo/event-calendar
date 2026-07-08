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
                () -> assertTrue(passwordService.verifyPassword(password, firstHash)),
                () -> assertTrue(passwordService.verifyPassword(password, secondHash)),
                () -> assertFalse(passwordService.verifyPassword("correct horse battery staple!", firstHash)),
                () -> assertFalse(passwordService.verifyPassword(null, firstHash)),
                () -> assertFalse(passwordService.verifyPassword(password, null)),
                () -> assertFalse(passwordService.verifyPassword(password, "not-a-supported-hash")));
    }

    @Test
    void rejectsWeakOrSelfReferentialPasswordsBeforeHashing() {
        assertAll(
                () -> assertThrows(ValidationException.class, () -> passwordService.hashPassword("piotr", "")),
                () -> assertThrows(ValidationException.class, () -> passwordService.hashPassword("piotr", "too-short")),
                () -> assertThrows(ValidationException.class, () -> passwordService.hashPassword("Piotr", "piotr")));
    }
}
