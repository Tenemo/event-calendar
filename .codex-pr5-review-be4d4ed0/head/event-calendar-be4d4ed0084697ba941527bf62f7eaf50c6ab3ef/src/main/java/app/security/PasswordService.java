package app.security;

import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import java.util.Arrays;
import java.util.Map;

@ApplicationScoped
@Named
public class PasswordService {
    static final String PASSWORD_HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int PASSWORD_HASH_ITERATIONS = 600_000;
    private static final String DUMMY_PASSWORD_HASH =
            PASSWORD_HASH_ALGORITHM
                    + ":"
                    + PASSWORD_HASH_ITERATIONS
                    + ":AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=:"
                    + "1CWgpzdZaXuiv+M7nJjALTxRC5d19dsMY6jY4Nm9n0E=";
    private static final int PASSWORD_HASH_SALT_BYTES = 32;
    private static final int PASSWORD_HASH_KEY_BYTES = 32;
    public static final int MINIMUM_PASSWORD_LENGTH = 8;
    public static final int MAXIMUM_PASSWORD_LENGTH = 512;

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
    }

    public int getMaximumPasswordLength() {
        return MAXIMUM_PASSWORD_LENGTH;
    }

    public void validatePasswordPolicy(String username, String password) {
        if (password == null || password.isBlank()) {
            throw new ValidationException("Password is required.");
        }
        int passwordLength = password.codePointCount(0, password.length());
        if (passwordLength < MINIMUM_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }
        if (passwordLength > MAXIMUM_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be " + MAXIMUM_PASSWORD_LENGTH + " characters or fewer.");
        }
        if (username != null && password.equalsIgnoreCase(username.trim())) {
            throw new ValidationException("Password must not match the username.");
        }
        if (password.codePoints().noneMatch(Character::isUpperCase)) {
            throw new ValidationException("Password must contain at least one uppercase letter.");
        }
        if (password.codePoints().noneMatch(Character::isDigit)) {
            throw new ValidationException("Password must contain at least one digit.");
        }
    }

    public String hashPassword(String username, String password) {
        validatePasswordPolicy(username, password);
        char[] passwordCharacters = password.toCharArray();
        try {
            return passwordHash.generate(passwordCharacters);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    public boolean verifyPassword(String password, String storedHash) {
        if (password == null || password.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (password.codePointCount(0, password.length()) > MAXIMUM_PASSWORD_LENGTH) {
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
        verifyPassword(password, DUMMY_PASSWORD_HASH);
    }

    private boolean verifyJakartaSecurityPasswordHash(char[] passwordCharacters, String storedHash) {
        try {
            return passwordHash.verify(passwordCharacters, storedHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
