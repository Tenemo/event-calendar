package app.security;

import app.util.ValidationException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import java.util.Arrays;
import java.util.Map;

@ApplicationScoped
@Named
public class PasswordService {
    static final String PASSWORD_HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int PASSWORD_HASH_ITERATIONS = 600_000;
    private static final int PASSWORD_HASH_SALT_BYTES = 32;
    private static final int PASSWORD_HASH_KEY_BYTES = 32;
    public static final int MINIMUM_PASSWORD_LENGTH = 8;
    public static final int MAXIMUM_PASSWORD_LENGTH = 512;

    @Inject
    private Pbkdf2PasswordHash passwordHash;

    private boolean passwordHashInitialized;

    @PostConstruct
    synchronized void initializePasswordHash() {
        if (passwordHashInitialized) {
            return;
        }
        if (passwordHash == null) {
            throw new IllegalStateException("Jakarta Security password hash is unavailable.");
        }

        passwordHash.initialize(Map.of(
                "Pbkdf2PasswordHash.Algorithm", PASSWORD_HASH_ALGORITHM,
                "Pbkdf2PasswordHash.Iterations", Integer.toString(PASSWORD_HASH_ITERATIONS),
                "Pbkdf2PasswordHash.SaltSizeBytes", Integer.toString(PASSWORD_HASH_SALT_BYTES),
                "Pbkdf2PasswordHash.KeySizeBytes", Integer.toString(PASSWORD_HASH_KEY_BYTES)));
        passwordHashInitialized = true;
    }

    public int getMaximumPasswordLength() {
        return MAXIMUM_PASSWORD_LENGTH;
    }

    public void validatePasswordPolicy(String username, String password) {
        if (password == null || password.isBlank()) {
            throw new ValidationException("Password is required.");
        }
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }
        if (password.length() > MAXIMUM_PASSWORD_LENGTH) {
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
            return configuredPasswordHash().generate(passwordCharacters);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    public boolean verifyPassword(String password, String storedHash) {
        if (password == null || password.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (password.length() > MAXIMUM_PASSWORD_LENGTH) {
            return false;
        }

        char[] passwordCharacters = password.toCharArray();
        try {
            if (isJakartaSecurityPasswordHash(storedHash)) {
                return verifyJakartaSecurityPasswordHash(passwordCharacters, storedHash);
            }
            return false;
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    private boolean verifyJakartaSecurityPasswordHash(char[] passwordCharacters, String storedHash) {
        try {
            return configuredPasswordHash().verify(passwordCharacters, storedHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isJakartaSecurityPasswordHash(String storedHash) {
        return storedHash.split(":", -1).length == 4;
    }

    private Pbkdf2PasswordHash configuredPasswordHash() {
        if (!passwordHashInitialized) {
            initializePasswordHash();
        }
        return passwordHash;
    }
}
