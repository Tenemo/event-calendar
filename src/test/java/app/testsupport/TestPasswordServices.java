package app.testsupport;

import app.security.PasswordService;
import jakarta.security.enterprise.identitystore.Pbkdf2PasswordHash;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestPasswordServices {
    private TestPasswordServices() {
    }

    public static PasswordService passwordService() {
        return passwordService(new RecordingPasswordHash());
    }

    public static PasswordService passwordService(Pbkdf2PasswordHash passwordHash) {
        PasswordService passwordService = new PasswordService();
        ServiceTestSupport.setField(passwordService, "passwordHash", passwordHash);
        initializePasswordService(passwordService);
        return passwordService;
    }

    private static void initializePasswordService(PasswordService passwordService) {
        try {
            var initializationMethod = PasswordService.class.getDeclaredMethod("initializePasswordHash");
            initializationMethod.setAccessible(true);
            initializationMethod.invoke(passwordService);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not initialize the password service for a test.", exception);
        }
    }

    public static final class RecordingPasswordHash implements Pbkdf2PasswordHash {
        private final Map<String, char[]> passwordCharactersByHash = new HashMap<>();
        private Map<String, String> initializedParameters = Map.of();
        private int initializationCount;
        private int generationCount;
        private int verificationCount;
        private String lastVerifiedHash;

        @Override
        public void initialize(Map<String, String> parameters) {
            initializationCount++;
            initializedParameters = new LinkedHashMap<>(parameters);
        }

        @Override
        public String generate(char[] passwordCharacters) {
            generationCount++;
            String salt = base64("salt-" + generationCount);
            String hash = base64("hash-" + generationCount);
            String generatedHash = "PBKDF2WithHmacSHA256:600000:" + salt + ":" + hash;
            passwordCharactersByHash.put(generatedHash, passwordCharacters.clone());
            return generatedHash;
        }

        @Override
        public boolean verify(char[] passwordCharacters, String hashedPassword) {
            verificationCount++;
            lastVerifiedHash = hashedPassword;
            char[] expectedPasswordCharacters = passwordCharactersByHash.get(hashedPassword);
            return expectedPasswordCharacters != null && Arrays.equals(expectedPasswordCharacters, passwordCharacters);
        }

        public Map<String, String> initializedParameters() {
            return initializedParameters;
        }

        public int initializationCount() {
            return initializationCount;
        }

        public int generationCount() {
            return generationCount;
        }

        public int verificationCount() {
            return verificationCount;
        }

        public String lastVerifiedHash() {
            return lastVerifiedHash;
        }

        private String base64(String value) {
            return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
