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
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PasswordServiceTest {
    @Test
    void hashesPasswordsThroughJakartaSecurityAndVerifiesOnlyTheCorrectPassword() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String password = "Correct horse battery staple 1";
        int generationsBeforeHashing = passwordHash.generationCount();

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
                () -> assertEquals(2, passwordHash.generationCount() - generationsBeforeHashing),
                () -> assertTrue(passwordService.verifyPassword(password, firstHash)),
                () -> assertTrue(passwordService.verifyPassword(password, secondHash)),
                () -> assertFalse(passwordService.verifyPassword("Correct horse battery staple 1!", firstHash)),
                () -> assertFalse(passwordService.verifyPassword(null, firstHash)),
                () -> assertFalse(passwordService.verifyPassword(password, null)),
                () -> assertFalse(passwordService.verifyPassword(password, "not-a-supported-hash")));
    }

    @Test
    void delegatesStoredHashParsingToJakartaSecurityAndMapsMalformedHashesToFailure() {
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
                () -> assertEquals(3, passwordHash.verificationCount()));
    }

    @Test
    void mapsProviderParsingFailuresForMalformedDatabaseHashesToGenericFailure() {
        PasswordService passwordService = passwordService(new MalformedHashRejectingPasswordHash());

        assertFalse(passwordService.verifyPassword(
                "correct horse battery staple",
                "malformed-database-hash"));
    }

    @Test
    void countsUnicodeCodePointsAsCharactersAtBothLengthBoundaries() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String supplementaryCharacter = new String(Character.toChars(0x1F600));
        String maximumLengthPassword =
                supplementaryCharacter.repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH);

        String hash = assertDoesNotThrow(() -> passwordService.hashPassword("piotr", maximumLengthPassword));

        assertAll(
                () -> assertEquals(
                        PasswordService.MAXIMUM_PASSWORD_INPUT_LENGTH,
                        maximumLengthPassword.length()),
                () -> assertEquals(
                        1_024,
                        passwordService.getMaximumPasswordInputLength()),
                () -> assertTrue(passwordService.verifyPassword(maximumLengthPassword, hash)),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        supplementaryCharacter.repeat(14),
                        "Password must be at least 15 characters."),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        maximumLengthPassword + "p",
                        "Password must be 512 characters or fewer."));
    }

    @Test
    void rejectsPasswordsThatBecomeBlankAfterPolicyNormalization() {
        PasswordService passwordService = passwordService();

        assertAll(
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        "\u00A0".repeat(PasswordService.MINIMUM_PASSWORD_LENGTH),
                        "Password is required."),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        "\u2007".repeat(PasswordService.MINIMUM_PASSWORD_LENGTH),
                        "Password is required."),
                () -> assertPasswordRejected(
                        passwordService,
                        "piotr",
                        "\u3000".repeat(PasswordService.MINIMUM_PASSWORD_LENGTH),
                        "Password is required."));
    }

    @Test
    void comparesUsernamesAndPasswordsWithTheSamePolicyNormalization() {
        PasswordService passwordService = passwordService();
        String username = "long-account-name";

        assertAll(
                () -> assertPasswordRejected(
                        passwordService,
                        username,
                        " \u00A0LONG-ACCOUNT-NAME\u3000 ",
                        "Password must not match the username."),
                () -> assertPasswordRejected(
                        passwordService,
                        username,
                        toFullWidthAscii(username),
                        "Password must not match the username."));
    }

    @Test
    void rejectsPasswordsThatViolateLengthOrIdentityRequirementsBeforeHashing() {
        PasswordService passwordService = passwordService();

        assertAll(
                () -> assertPasswordRejected(passwordService, "piotr", "", "Password is required."),
                () -> assertPasswordRejected(
                        passwordService, "piotr", "fourteen-chars", "Password must be at least 15 characters."),
                () -> assertPasswordRejected(
                        passwordService,
                        "long-account-password",
                        "LONG-ACCOUNT-PASSWORD",
                        "Password must not match the username."),
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
    void absentAccountsCostTheSameKeyDerivationAsAWrongPassword() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        String realUserHash = passwordService.hashPassword("piotr", "Correct horse battery staple 1");
        int verificationsBeforeAbsentAccount = passwordHash.verificationCount();

        passwordService.verifyMissingUserPassword("Correct horse battery staple 1");

        assertAll(
                () -> assertEquals(
                        verificationsBeforeAbsentAccount + 1,
                        passwordHash.verificationCount(),
                        "A missing account must still reach the key derivation, or timing reveals that it is missing."),
                () -> assertTrue(
                        passwordHash.lastVerifiedHash().startsWith("PBKDF2WithHmacSHA256:600000:"),
                        "The absent-account hash must use the configured algorithm and iteration count."),
                () -> assertNotEquals(
                        realUserHash,
                        passwordHash.lastVerifiedHash(),
                        "The absent-account hash must not be a real account's stored hash."));
    }

    @Test
    void absentAccountVerificationIsNotShortCircuitedByPolicyViolatingGuesses() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);

        passwordService.verifyMissingUserPassword("short");
        passwordService.verifyMissingUserPassword("nouppercaseordigit");
        passwordService.verifyMissingUserPassword("a".repeat(PasswordService.MAXIMUM_PASSWORD_LENGTH));

        assertEquals(
                3,
                passwordHash.verificationCount(),
                "Password policy must not short-circuit the absent-account key derivation, "
                        + "because policy-violating guesses would then be answered faster.");
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

    private static String toFullWidthAscii(String value) {
        StringBuilder fullWidthValue = new StringBuilder(value.length());
        value.codePoints().forEach(character -> fullWidthValue.appendCodePoint(
                character >= '!' && character <= '~'
                        ? character + 0xFEE0
                        : character));
        return fullWidthValue.toString();
    }

    private static final class MalformedHashRejectingPasswordHash implements Pbkdf2PasswordHash {
        @Override
        public void initialize(Map<String, String> parameters) {
        }

        @Override
        public String generate(char[] passwordCharacters) {
            return "PBKDF2WithHmacSHA256:600000:encoded-salt:encoded-hash";
        }

        @Override
        public boolean verify(char[] passwordCharacters, String hashedPassword) {
            throw new IllegalArgumentException("Malformed password hash.");
        }
    }
}
