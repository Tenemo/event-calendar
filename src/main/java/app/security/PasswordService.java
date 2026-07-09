package app.security;

import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@ApplicationScoped
@Named
public class PasswordService {
    private static final String HASH_PREFIX = "pbkdf2_sha256";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int MINIMUM_PASSWORD_LENGTH = 14;
    public static final int MAXIMUM_PASSWORD_LENGTH = 512;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int ITERATIONS = 600_000;
    private static final int MINIMUM_STORED_HASH_ITERATIONS = 100_000;
    private static final int MAXIMUM_STORED_HASH_ITERATIONS = ITERATIONS;

    private final SecureRandom secureRandom = new SecureRandom();

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
    }

    public String hashPassword(String username, String password) {
        validatePasswordPolicy(username, password);

        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = deriveKey(password, salt, ITERATIONS);

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return HASH_PREFIX + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(hash);
    }

    public boolean verifyPassword(String password, String storedHash) {
        if (password == null || password.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        if (password.length() > MAXIMUM_PASSWORD_LENGTH) {
            return false;
        }

        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !HASH_PREFIX.equals(parts[0])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            if (!isSupportedStoredHashIterationCount(iterations)) {
                return false;
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[2]);
            byte[] expectedHash = decoder.decode(parts[3]);
            byte[] actualHash = deriveKey(password, salt, iterations);
            return constantTimeEquals(expectedHash, actualHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isSupportedStoredHashIterationCount(int iterations) {
        return iterations >= MINIMUM_STORED_HASH_ITERATIONS && iterations <= MAXIMUM_STORED_HASH_ITERATIONS;
    }

    private byte[] deriveKey(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BYTES * 8);
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            return secretKeyFactory.generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable.", exception);
        }
    }

    private boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return false;
        }

        int difference = 0;
        for (int index = 0; index < expected.length; index++) {
            difference |= expected[index] ^ actual[index];
        }
        return difference == 0;
    }
}
