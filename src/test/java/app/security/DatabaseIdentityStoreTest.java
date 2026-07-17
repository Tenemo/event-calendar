package app.security;

import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.user.ApplicationUser;
import app.user.UserService;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class DatabaseIdentityStoreTest {
    @Test
    void authenticationSourceUsesTheSharedDeploymentAwareResolver() {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getRemoteAddr" -> "203.0.113.25";
                    case "getHeaders" -> Collections.enumeration(List.of("198.51.100.18"));
                    default -> throw new AssertionError("Unsupported request method: " + method.getName());
                });
        DatabaseIdentityStore identityStore = new DatabaseIdentityStore();
        setField(identityStore, "request", request);
        setField(identityStore, "clientRequestSourceResolver", new ClientRequestSourceResolver(null));

        assertEquals("203.0.113.25", identityStore.sourceIdentifier());
    }

    @Test
    void missingUsersStillRunPasswordVerificationAgainstDummyHash() {
        RecordingPasswordService passwordService = new RecordingPasswordService(false);
        DatabaseIdentityStore identityStore = identityStore(new FixedUserService(Optional.empty()), passwordService);

        CredentialValidationResult result = identityStore.validate(new UsernamePasswordCredential("missing", "wrong-password"));

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.INVALID, result.getStatus()),
                () -> assertEquals(1, passwordService.verificationCount),
                () -> assertEquals("wrong-password", passwordService.lastPassword),
                () -> assertTrue(passwordService.lastStoredHash.startsWith(
                        PasswordService.PASSWORD_HASH_ALGORITHM
                                + ":"
                                + PasswordService.PASSWORD_HASH_ITERATIONS
                                + ":")));
    }

    @Test
    void validUsersVerifyAgainstTheirStoredHash() {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("piotr");
        user.setPasswordHash("stored-user-hash");
        user.setPasswordVersion(7);
        user.setActive(true);
        RecordingPasswordService passwordService = new RecordingPasswordService(true);
        PasswordValidationState passwordValidationState = new PasswordValidationState();
        DatabaseIdentityStore identityStore = identityStore(
                new FixedUserService(Optional.of(user)),
                passwordService,
                new LoginAttemptThrottle(),
                new MutableRequestSource("192.0.2.1"),
                passwordValidationState);

        CredentialValidationResult result = identityStore.validate(new UsernamePasswordCredential("Piotr", "correct-password"));
        OptionalLong validatedPasswordVersion = passwordValidationState.consumeValidatedPasswordVersion(
                result.getCallerPrincipal());

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.VALID, result.getStatus()),
                () -> assertEquals("piotr", result.getCallerPrincipal().getName()),
                () -> assertEquals(7, validatedPasswordVersion.orElseThrow()),
                () -> assertTrue(result.getCallerGroups().contains("USER")),
                () -> assertEquals(Set.of("USER"), result.getCallerGroups()),
                () -> assertEquals(1, passwordService.verificationCount),
                () -> assertEquals("correct-password", passwordService.lastPassword),
                () -> assertEquals("stored-user-hash", passwordService.lastStoredHash));
    }

    @Test
    void concurrentPasswordChangeCannotStampAnOldPasswordLoginWithTheNewVersion() throws Exception {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("piotr");
        user.setPasswordHash("old-password-hash");
        user.setPasswordVersion(12);
        user.setActive(true);
        CountDownLatch verificationStarted = new CountDownLatch(1);
        CountDownLatch continueVerification = new CountDownLatch(1);
        BlockingPasswordService passwordService = new BlockingPasswordService(
                verificationStarted,
                continueVerification);
        PasswordValidationState passwordValidationState = new PasswordValidationState();
        DatabaseIdentityStore identityStore = identityStore(
                new FixedUserService(Optional.of(user)),
                passwordService,
                new LoginAttemptThrottle(),
                new MutableRequestSource("192.0.2.1"),
                passwordValidationState);
        ExecutorService authenticationExecutor = Executors.newSingleThreadExecutor();

        try {
            Future<CredentialValidationResult> pendingAuthentication = authenticationExecutor.submit(
                    () -> identityStore.validate(new UsernamePasswordCredential("piotr", "old-password")));
            assertTrue(verificationStarted.await(5, TimeUnit.SECONDS));

            user.setPasswordHash("new-password-hash");
            user.setPasswordVersion(13);
            continueVerification.countDown();

            CredentialValidationResult result = pendingAuthentication.get(5, TimeUnit.SECONDS);
            OptionalLong validatedPasswordVersion = passwordValidationState.consumeValidatedPasswordVersion(
                    result.getCallerPrincipal());
            assertAll(
                    () -> assertEquals("old-password-hash", passwordService.validatedPasswordHash),
                    () -> assertEquals(12, validatedPasswordVersion.orElseThrow()),
                    () -> assertEquals(13, user.getPasswordVersion()));
        } finally {
            continueVerification.countDown();
            authenticationExecutor.shutdownNow();
        }
    }

    @Test
    void callerGroupsAreReloadedForActiveUsers() {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("piotr");
        user.setPasswordHash("stored-user-hash");
        user.setActive(true);
        RecordingPasswordService passwordService = new RecordingPasswordService(true);
        DatabaseIdentityStore identityStore = identityStore(new FixedUserService(Optional.of(user)), passwordService);

        CredentialValidationResult result = identityStore.validate(new UsernamePasswordCredential("Piotr", "correct-password"));

        assertAll(
                () -> assertTrue(result.getCallerGroups().contains("USER")),
                () -> assertEquals(Set.of("USER"), identityStore.getCallerGroups(result)));
    }

    @Test
    void normalizedUsernameFailuresStopFurtherPasswordVerification() {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("piotr");
        user.setPasswordHash("stored-user-hash");
        user.setActive(true);
        RecordingPasswordService passwordService = new RecordingPasswordService(false);
        DatabaseIdentityStore identityStore = identityStore(new FixedUserService(Optional.of(user)), passwordService);

        for (int failedAttemptIndex = 0;
                failedAttemptIndex < LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE;
                failedAttemptIndex++) {
            String username = failedAttemptIndex % 2 == 0 ? " Piotr " : "PIOTR";
            CredentialValidationResult result = identityStore.validate(
                    new UsernamePasswordCredential(username, "wrong-password"));
            assertEquals(CredentialValidationResult.Status.INVALID, result.getStatus());
        }

        CredentialValidationResult blockedResult = identityStore.validate(
                new UsernamePasswordCredential("piotr", "correct-password"));

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.INVALID, blockedResult.getStatus()),
                () -> assertEquals(
                        LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE,
                        passwordService.verificationCount));
    }

    @Test
    void hostileSourceCannotLockTheUsernameForALegitimateSource() {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("piotr");
        user.setPasswordHash("stored-user-hash");
        user.setActive(true);
        RecordingPasswordService passwordService = new RecordingPasswordService(false);
        MutableRequestSource requestSource = new MutableRequestSource("198.51.100.10");
        DatabaseIdentityStore identityStore = identityStore(
                new FixedUserService(Optional.of(user)),
                passwordService,
                new LoginAttemptThrottle(),
                requestSource);

        for (int failedAttemptIndex = 0;
                failedAttemptIndex < LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE;
                failedAttemptIndex++) {
            identityStore.validate(new UsernamePasswordCredential("piotr", "wrong-password"));
        }

        passwordService.verificationResult = true;
        requestSource.sourceIdentifier = "203.0.113.20";
        CredentialValidationResult legitimateResult = identityStore.validate(
                new UsernamePasswordCredential("piotr", "correct-password"));

        requestSource.sourceIdentifier = "198.51.100.10";
        CredentialValidationResult hostileSourceResult = identityStore.validate(
                new UsernamePasswordCredential("piotr", "correct-password"));

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.VALID, legitimateResult.getStatus()),
                () -> assertEquals(CredentialValidationResult.Status.INVALID, hostileSourceResult.getStatus()),
                () -> assertEquals(
                        LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE + 1,
                        passwordService.verificationCount));
    }

    @Test
    void missingAndExistingUsersUseTheSameThrottlePolicy() {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("existing");
        user.setPasswordHash("stored-user-hash");
        user.setActive(true);
        RecordingPasswordService existingUserPasswordService = new RecordingPasswordService(false);
        RecordingPasswordService missingUserPasswordService = new RecordingPasswordService(false);
        DatabaseIdentityStore existingUserIdentityStore = identityStore(
                new FixedUserService(Optional.of(user)), existingUserPasswordService);
        DatabaseIdentityStore missingUserIdentityStore = identityStore(
                new FixedUserService(Optional.empty()), missingUserPasswordService);

        for (int failedAttemptIndex = 0;
                failedAttemptIndex <= LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE;
                failedAttemptIndex++) {
            existingUserIdentityStore.validate(new UsernamePasswordCredential("existing", "wrong-password"));
            missingUserIdentityStore.validate(new UsernamePasswordCredential("missing", "wrong-password"));
        }

        assertAll(
                () -> assertEquals(
                        LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE,
                        existingUserPasswordService.verificationCount),
                () -> assertEquals(
                        LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE,
                        missingUserPasswordService.verificationCount),
                () -> assertFalse(existingUserPasswordService.lastStoredHash.startsWith("PBKDF2WithHmacSHA256:")),
                () -> assertTrue(missingUserPasswordService.lastStoredHash.startsWith(
                        PasswordService.PASSWORD_HASH_ALGORITHM
                                + ":"
                                + PasswordService.PASSWORD_HASH_ITERATIONS
                                + ":")));
    }

    @Test
    void successfulAuthenticationClearsPriorFailures() {
        ApplicationUser user = new ApplicationUser();
        user.setUsername("piotr");
        user.setPasswordHash("stored-user-hash");
        user.setActive(true);
        RecordingPasswordService passwordService = new RecordingPasswordService(false);
        DatabaseIdentityStore identityStore = identityStore(new FixedUserService(Optional.of(user)), passwordService);

        for (int failedAttemptIndex = 0;
                failedAttemptIndex < LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE - 1;
                failedAttemptIndex++) {
            identityStore.validate(new UsernamePasswordCredential("piotr", "wrong-password"));
        }
        passwordService.verificationResult = true;
        CredentialValidationResult successfulResult = identityStore.validate(
                new UsernamePasswordCredential("piotr", "correct-password"));
        passwordService.verificationResult = false;
        for (int failedAttemptIndex = 0;
                failedAttemptIndex < LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE;
                failedAttemptIndex++) {
            identityStore.validate(new UsernamePasswordCredential("piotr", "wrong-password"));
        }
        identityStore.validate(new UsernamePasswordCredential("piotr", "wrong-password"));

        assertAll(
                () -> assertEquals(CredentialValidationResult.Status.VALID, successfulResult.getStatus()),
                () -> assertEquals(
                        LoginAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE * 2,
                        passwordService.verificationCount));
    }

    private static DatabaseIdentityStore identityStore(UserService userService, PasswordService passwordService) {
        return identityStore(
                userService,
                passwordService,
                new LoginAttemptThrottle(),
                new MutableRequestSource("192.0.2.1"),
                new PasswordValidationState());
    }

    private static DatabaseIdentityStore identityStore(
            UserService userService,
            PasswordService passwordService,
            LoginAttemptThrottle loginAttemptThrottle,
            MutableRequestSource requestSource) {
        return identityStore(
                userService,
                passwordService,
                loginAttemptThrottle,
                requestSource,
                new PasswordValidationState());
    }

    private static DatabaseIdentityStore identityStore(
            UserService userService,
            PasswordService passwordService,
            LoginAttemptThrottle loginAttemptThrottle,
            MutableRequestSource requestSource,
            PasswordValidationState passwordValidationState) {
        DatabaseIdentityStore identityStore = new DatabaseIdentityStore() {
            @Override
            String sourceIdentifier() {
                return requestSource.sourceIdentifier;
            }
        };
        setField(identityStore, "userService", userService);
        setField(identityStore, "passwordService", passwordService);
        setField(identityStore, "loginAttemptThrottle", loginAttemptThrottle);
        setField(identityStore, "passwordValidationState", passwordValidationState);
        return identityStore;
    }

    private static final class FixedUserService extends UserService {
        private final Optional<ApplicationUser> user;

        private FixedUserService(Optional<ApplicationUser> user) {
            this.user = user;
        }

        @Override
        public Optional<ApplicationUser> findActiveByUsername(String username) {
            return user;
        }
    }

    private static final class RecordingPasswordService extends PasswordService {
        private boolean verificationResult;
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

    private static final class BlockingPasswordService extends PasswordService {
        private final CountDownLatch verificationStarted;
        private final CountDownLatch continueVerification;
        private String validatedPasswordHash;

        private BlockingPasswordService(
                CountDownLatch verificationStarted,
                CountDownLatch continueVerification) {
            this.verificationStarted = verificationStarted;
            this.continueVerification = continueVerification;
        }

        @Override
        public boolean verifyPassword(String password, String storedHash) {
            validatedPasswordHash = storedHash;
            verificationStarted.countDown();
            try {
                if (!continueVerification.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Password verification was not resumed.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Password verification was interrupted.", exception);
            }
            return true;
        }
    }

    private static final class MutableRequestSource {
        private String sourceIdentifier;

        private MutableRequestSource(String sourceIdentifier) {
            this.sourceIdentifier = sourceIdentifier;
        }

    }
}
