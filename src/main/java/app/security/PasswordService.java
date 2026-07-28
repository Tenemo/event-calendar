package app.security;

import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@ApplicationScoped
@Named
public class PasswordService {
    static final String PASSWORD_HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int PASSWORD_HASH_ITERATIONS = 600_000;
    private static final int PASSWORD_HASH_SALT_BYTES = 32;
    private static final int PASSWORD_HASH_KEY_BYTES = 32;
    public static final int MINIMUM_PASSWORD_LENGTH = 15;
    public static final int MAXIMUM_PASSWORD_LENGTH = 512;
    public static final int MAXIMUM_PASSWORD_INPUT_LENGTH = MAXIMUM_PASSWORD_LENGTH * 2;

    /**
     * Verified instead of a real stored hash when the username does not exist, so that a missing
     * account costs the same key derivation as a wrong password and cannot be told apart by
     * response timing. It is derived from the configured parameters at startup rather than written
     * as a literal, because a literal that stops matching the configured salt or key size would
     * make verification fail early and silently restore the timing difference.
     */
    private String absentUserPasswordHash;

    @Inject
    private Pbkdf2PasswordHash passwordHash;

    @PostConstruct
    void initializePasswordHash() {
        if (passwordHash == null) {
            throw new IllegalStateException("Jakarta Security password hash is unavailable.");
        }
        passwordHash.initialize(Map.of(
                "Pbkdf2PasswordHash.Algorithm", PASSWORD_HASH_ALGORITHM,
                "Pbkdf2PasswordHash.Iterations", Integer.toString(PASSWORD_HASH_ITERATIONS),
                "Pbkdf2PasswordHash.SaltSizeBytes", Integer.toString(PASSWORD_HASH_SALT_BYTES),
                "Pbkdf2PasswordHash.KeySizeBytes", Integer.toString(PASSWORD_HASH_KEY_BYTES)));
        absentUserPasswordHash = generateAbsentUserPasswordHash();
    }

    private String generateAbsentUserPasswordHash() {
        char[] unusablePasswordCharacters = ("absent-account-placeholder-"
                + PASSWORD_HASH_ALGORITHM + "-" + PASSWORD_HASH_ITERATIONS).toCharArray();
        try {
            return generatePasswordHash(unusablePasswordCharacters);
        } finally {
            Arrays.fill(unusablePasswordCharacters, '\0');
        }
    }

    public int getMaximumPasswordInputLength() {
        return MAXIMUM_PASSWORD_INPUT_LENGTH;
    }

    void validatePasswordPolicy(String username, String password) {
        String normalizedPassword = normalizedCredentialForPolicyComparison(password);
        if (normalizedPassword.isBlank()) {
            throw new ValidationException("Password is required.");
        }
        int passwordLength = password.codePointCount(0, password.length());
        if (passwordLength < MINIMUM_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }
        if (passwordLength > MAXIMUM_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be " + MAXIMUM_PASSWORD_LENGTH + " characters or fewer.");
        }
        if (normalizedPassword.equals(normalizedCredentialForPolicyComparison(username))) {
            throw new ValidationException("Password must not match the username.");
        }
    }

    private String normalizedCredentialForPolicyComparison(String credential) {
        if (credential == null) {
            return "";
        }
        return Normalizer.normalize(credential, Normalizer.Form.NFKC)
                .strip()
                .toLowerCase(Locale.ROOT);
    }

    public String hashPassword(String username, String password) {
        validatePasswordPolicy(username, password);
        char[] passwordCharacters = password.toCharArray();
        try {
            return generatePasswordHash(passwordCharacters);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    public boolean verifyPassword(String password, String storedHash) {
        if (!isPasswordVerificationCandidate(password, storedHash)) {
            return false;
        }

        char[] passwordCharacters = password.toCharArray();
        try {
            return verifyJakartaSecurityPasswordHash(passwordCharacters, storedHash);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    void verifyMissingUserPassword(String password) {
        if (absentUserPasswordHash == null) {
            throw new IllegalStateException("The absent-account password hash was not initialized.");
        }
        verifyPassword(password, absentUserPasswordHash);
    }

    private String generatePasswordHash(char[] passwordCharacters) {
        return passwordHash.generate(passwordCharacters);
    }

    private boolean isPasswordVerificationCandidate(
            String password,
            String storedHash) {
        return password != null
                && !password.isBlank()
                && storedHash != null
                && !storedHash.isBlank()
                && password.codePointCount(0, password.length()) <= MAXIMUM_PASSWORD_LENGTH;
    }

    private boolean verifyJakartaSecurityPasswordHash(char[] passwordCharacters, String storedHash) {
        try {
            return passwordHash.verify(passwordCharacters, storedHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
