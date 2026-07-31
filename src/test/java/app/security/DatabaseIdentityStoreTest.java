package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.user.ApplicationUser;
import app.user.UserService;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DatabaseIdentityStoreTest {
    @Test
    void enabledLocalCredentialAuthenticatesOnlyTheFixedSeededAdmin() {
        ApplicationUser admin = user("admin");
        RecordingUserService userService = new RecordingUserService(Optional.of(admin));
        DatabaseIdentityStore identityStore = identityStore(
                userService,
                new LocalDevelopmentAutoSignIn("true", "http://localhost:9080"));

        CredentialValidationResult result =
                identityStore.validate(new LocalDevelopmentAutoSignInCredential());

        assertAll(
                () -> assertEquals(
                        CredentialValidationResult.Status.VALID, result.getStatus()),
                () -> assertEquals("admin", result.getCallerPrincipal().getName()),
                () -> assertEquals(1, userService.lookupCount),
                () -> assertEquals("admin", userService.lastUsername));
    }

    @Test
    void disabledLocalCredentialFailsWithoutLookingUpAnyAccount() {
        RecordingUserService userService =
                new RecordingUserService(Optional.of(user("admin")));
        DatabaseIdentityStore identityStore = identityStore(
                userService,
                new LocalDevelopmentAutoSignIn(null, null));

        CredentialValidationResult result =
                identityStore.validate(new LocalDevelopmentAutoSignInCredential());

        assertAll(
                () -> assertEquals(
                        CredentialValidationResult.Status.INVALID, result.getStatus()),
                () -> assertNull(result.getCallerPrincipal()),
                () -> assertEquals(0, userService.lookupCount));
    }

    @Test
    void enabledLocalCredentialFailsClosedWhenTheSeededAdminIsMissing() {
        RecordingUserService userService = new RecordingUserService(Optional.empty());
        DatabaseIdentityStore identityStore = identityStore(
                userService,
                new LocalDevelopmentAutoSignIn("true", "http://127.0.0.1"));

        CredentialValidationResult result =
                identityStore.validate(new LocalDevelopmentAutoSignInCredential());

        assertAll(
                () -> assertEquals(
                        CredentialValidationResult.Status.INVALID, result.getStatus()),
                () -> assertEquals(1, userService.lookupCount),
                () -> assertEquals("admin", userService.lastUsername));
    }

    @Test
    void unrelatedCredentialRemainsAvailableForOtherIdentityStores() {
        RecordingUserService userService = new RecordingUserService(Optional.empty());
        DatabaseIdentityStore identityStore = identityStore(
                userService,
                new LocalDevelopmentAutoSignIn("true", "http://localhost"));

        CredentialValidationResult result = identityStore.validate(new Credential() {
        });

        assertAll(
                () -> assertEquals(
                        CredentialValidationResult.Status.NOT_VALIDATED, result.getStatus()),
                () -> assertEquals(0, userService.lookupCount));
    }

    private DatabaseIdentityStore identityStore(
            UserService userService,
            LocalDevelopmentAutoSignIn localDevelopmentAutoSignIn) {
        DatabaseIdentityStore identityStore = new DatabaseIdentityStore();
        setField(identityStore, "userService", userService);
        setField(
                identityStore,
                "localDevelopmentAutoSignIn",
                localDevelopmentAutoSignIn);
        return identityStore;
    }

    private ApplicationUser user(String username) {
        ApplicationUser user = new ApplicationUser();
        user.setUsername(username);
        user.setDisplayName("Local administrator");
        return user;
    }

    private static final class RecordingUserService extends UserService {
        private final Optional<ApplicationUser> result;
        private int lookupCount;
        private String lastUsername;

        private RecordingUserService(Optional<ApplicationUser> result) {
            this.result = result;
        }

        @Override
        public Optional<ApplicationUser> findByUsername(String username) {
            lookupCount++;
            lastUsername = username;
            return result;
        }
    }
}
