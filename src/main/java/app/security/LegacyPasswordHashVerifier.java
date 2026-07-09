package app.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class LegacyPasswordHashVerifier {
    private static final String HASH_PREFIX = "pbkdf2_sha256";
    private static final String KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int HASH_BYTES = 32;
    private static final int MINIMUM_STORED_HASH_ITERATIONS = 100_000;

    private final int maximumStoredHashIterations;

    LegacyPasswordHashVerifier(int maximumStoredHashIterations) {
        this.maximumStoredHashIterations = maximumStoredHashIterations;
    }

    boolean verifyPassword(char[] passwordCharacters, String storedHash) {
        String[] parts = storedHash.split("\\$", -1);
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
            byte[] actualHash = deriveKey(passwordCharacters, salt, iterations);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isSupportedStoredHashIterationCount(int iterations) {
        return iterations >= MINIMUM_STORED_HASH_ITERATIONS && iterations <= maximumStoredHashIterations;
    }

    private byte[] deriveKey(char[] passwordCharacters, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(passwordCharacters, salt, iterations, HASH_BYTES * 8);
        try {
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM);
            return secretKeyFactory.generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Legacy password hash verification is unavailable.", exception);
        } finally {
            keySpec.clearPassword();
        }
    }
}
