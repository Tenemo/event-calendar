package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.user.AppUser;
import app.user.UserService;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DatabaseIdentityStoreTest {
    @Test
    void missingUsersStillRunPasswordVerificationAgainstDummyHash() {
        RecordingPasswordService passwordService = new RecordingPasswordService(false);
        DatabaseIdentityStore identityStore = identityStore(new FixedUserService(Optional.empty()), passwordService);

        CredentialValidationResult result = identityStore.validate(new UsernamePasswordCredential("missing", "wrong-password"));

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.INVALID, result.getStatus()),
                () -> assertEquals(1, passwordService.verificationCount),
                () -> assertEquals("wrong-password", passwordService.lastPassword),
                () -> assertTrue(passwordService.lastStoredHash.startsWith("pbkdf2_sha256$600000$")));
    }

    @Test
    void validUsersVerifyAgainstTheirStoredHash() {
        AppUser user = new AppUser();
        user.setUsername("piotr");
        user.setPasswordHash("stored-user-hash");
        user.setActive(true);
        RecordingPasswordService passwordService = new RecordingPasswordService(true);
        DatabaseIdentityStore identityStore = identityStore(new FixedUserService(Optional.of(user)), passwordService);

        CredentialValidationResult result = identityStore.validate(new UsernamePasswordCredential("Piotr", "correct-password"));

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.VALID, result.getStatus()),
                () -> assertEquals("piotr", result.getCallerPrincipal().getName()),
                () -> assertEquals(1, passwordService.verificationCount),
                () -> assertEquals("correct-password", passwordService.lastPassword),
                () -> assertEquals("stored-user-hash", passwordService.lastStoredHash));
    }

    private static DatabaseIdentityStore identityStore(UserService userService, PasswordService passwordService) {
        DatabaseIdentityStore identityStore = new DatabaseIdentityStore();
        setField(identityStore, "userService", userService);
        setField(identityStore, "passwordService", passwordService);
        return identityStore;
    }

    private static final class FixedUserService extends UserService {
        private final Optional<AppUser> user;

        private FixedUserService(Optional<AppUser> user) {
            this.user = user;
        }

        @Override
        public Optional<AppUser> findActiveByUsername(String username) {
            return user;
        }
    }

    private static final class RecordingPasswordService extends PasswordService {
        private final boolean verificationResult;
        private int verificationCount;
        private String lastPassword;
        private String lastStoredHash;

        private RecordingPasswordService(boolean verificationResult) {
            this.verificationResult = verificationResult;
        }

        @Override
        public boolean verifyPassword(String password, String storedHash) {
            verificationCount++;
            lastPassword = password;
            lastStoredHash = storedHash;
            return verificationResult;
        }
    }
}
