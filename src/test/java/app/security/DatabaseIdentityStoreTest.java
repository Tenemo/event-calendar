package app.security;

import static app.testsupport.ProxyReturnValues.defaultValue;
import static app.testsupport.ServiceTestSupport.setField;
import static app.testsupport.TestPasswordServices.passwordService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.testsupport.TestPasswordServices.RecordingPasswordHash;
import app.user.ApplicationUser;
import app.user.UserService;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DatabaseIdentityStoreTest {
    private static final String VALID_PASSWORD = "correct horse battery staple";

    @Test
    void missingUsersStillPayOnePasswordVerification() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        StubUserService userService = new StubUserService();
        StoreHarness harness = storeHarness(
                userService,
                passwordService,
                new SignInAttemptThrottle(),
                "192.0.2.10");
        int verificationCountBeforeAttempt = passwordHash.verificationCount();

        CredentialValidationResult result = harness.store().validate(
                credential("missing", VALID_PASSWORD));

        assertEquals(CredentialValidationResult.Status.INVALID, result.getStatus());
        assertEquals(verificationCountBeforeAttempt + 1, passwordHash.verificationCount());
        assertEquals(1, userService.lookupCount);
    }

    @Test
    void validUsersVerifyTheirHashAndRecordTheValidatedPasswordVersion() {
        RecordingPasswordHash passwordHash = new RecordingPasswordHash();
        PasswordService passwordService = passwordService(passwordHash);
        StubUserService userService = new StubUserService();
        ApplicationUser user = activeUser(
                "piotr",
                passwordService.hashPassword("piotr", VALID_PASSWORD),
                7);
        userService.users.put("piotr", user);
        StoreHarness harness = storeHarness(
                userService,
                passwordService,
                new SignInAttemptThrottle(),
                "192.0.2.10");

        CredentialValidationResult result = harness.store().validate(
                credential("  PIOTR  ", VALID_PASSWORD));
        OptionalLong validatedVersion = harness.passwordValidationState()
                .consumeValidatedPasswordVersion(result.getCallerPrincipal());

        assertEquals(CredentialValidationResult.Status.VALID, result.getStatus());
        assertEquals("piotr", result.getCallerPrincipal().getName());
        assertEquals(OptionalLong.of(7), validatedVersion);
    }

    @Test
    void fiveWrongPasswordsBlockOnlyThatUsernameAndSourcePair() {
        PasswordService passwordService = passwordService();
        StubUserService userService = new StubUserService();
        userService.users.put(
                "piotr",
                activeUser(
                        "piotr",
                        passwordService.hashPassword("piotr", VALID_PASSWORD),
                        1));
        SignInAttemptThrottle throttle = new SignInAttemptThrottle();
        StoreHarness hostileSource = storeHarness(
                userService,
                passwordService,
                throttle,
                "192.0.2.10");

        for (int attemptIndex = 0;
                attemptIndex < SignInAttemptThrottle.MAXIMUM_FAILED_ATTEMPTS_PER_USERNAME_AND_SOURCE;
                attemptIndex++) {
            assertEquals(
                    CredentialValidationResult.Status.INVALID,
                    hostileSource.store().validate(
                            credential("piotr", "wrong password value")).getStatus());
        }
        int lookupCountAtLimit = userService.lookupCount;

        assertEquals(
                CredentialValidationResult.Status.INVALID,
                hostileSource.store().validate(
                        credential("piotr", VALID_PASSWORD)).getStatus());
        assertEquals(lookupCountAtLimit, userService.lookupCount);

        StoreHarness differentSource = storeHarness(
                userService,
                passwordService,
                throttle,
                "192.0.2.11");
        assertEquals(
                CredentialValidationResult.Status.VALID,
                differentSource.store().validate(
                        credential("piotr", VALID_PASSWORD)).getStatus());
    }

    @Test
    void successfulAuthenticationClearsThePairFailureBudget() {
        PasswordService passwordService = passwordService();
        StubUserService userService = new StubUserService();
        userService.users.put(
                "piotr",
                activeUser(
                        "piotr",
                        passwordService.hashPassword("piotr", VALID_PASSWORD),
                        1));
        StoreHarness harness = storeHarness(
                userService,
                passwordService,
                new SignInAttemptThrottle(),
                "192.0.2.10");

        for (int attemptIndex = 0; attemptIndex < 4; attemptIndex++) {
            harness.store().validate(credential("piotr", "wrong password value"));
        }
        assertEquals(
                CredentialValidationResult.Status.VALID,
                harness.store().validate(credential("piotr", VALID_PASSWORD)).getStatus());
        for (int attemptIndex = 0; attemptIndex < 5; attemptIndex++) {
            assertEquals(
                    CredentialValidationResult.Status.INVALID,
                    harness.store().validate(
                            credential("piotr", "wrong password value")).getStatus());
        }
        assertEquals(
                CredentialValidationResult.Status.INVALID,
                harness.store().validate(credential("piotr", VALID_PASSWORD)).getStatus());
    }

    @Test
    void lookupFailuresDoNotConsumeTheAttemptBudget() {
        PasswordService passwordService = passwordService();
        RuntimeException lookupFailure = new IllegalStateException("Database lookup failed.");
        UserService failingUserService = new UserService() {
            @Override
            public String normalizeUsername(String username) {
                return normalizedUsername(username);
            }

            @Override
            public Optional<ApplicationUser> findActiveByUsername(String username) {
                throw lookupFailure;
            }
        };
        SignInAttemptThrottle throttle = new SignInAttemptThrottle();
        StoreHarness failingHarness = storeHarness(
                failingUserService,
                passwordService,
                throttle,
                "192.0.2.10");

        for (int attemptIndex = 0; attemptIndex < 10; attemptIndex++) {
            assertEquals(
                    lookupFailure,
                    assertThrows(
                            RuntimeException.class,
                            () -> failingHarness.store().validate(
                                    credential("piotr", VALID_PASSWORD))));
        }

        StubUserService workingUserService = new StubUserService();
        workingUserService.users.put(
                "piotr",
                activeUser(
                        "piotr",
                        passwordService.hashPassword("piotr", VALID_PASSWORD),
                        1));
        StoreHarness workingHarness = storeHarness(
                workingUserService,
                passwordService,
                throttle,
                "192.0.2.10");
        assertEquals(
                CredentialValidationResult.Status.VALID,
                workingHarness.store().validate(
                        credential("piotr", VALID_PASSWORD)).getStatus());
    }

    @Test
    void callerGroupsAreReturnedOnlyForActiveUsers() {
        StubUserService userService = new StubUserService();
        ApplicationUser user = activeUser("piotr", "hash", 1);
        userService.users.put("piotr", user);
        DatabaseIdentityStore store = storeHarness(
                        userService,
                        passwordService(),
                        new SignInAttemptThrottle(),
                        "192.0.2.10")
                .store();
        CredentialValidationResult validationResult = new CredentialValidationResult(
                "piotr",
                Set.of("USER"));

        assertEquals(Set.of("USER"), store.getCallerGroups(validationResult));
        user.setActive(false);
        assertEquals(Set.of(), store.getCallerGroups(validationResult));
        assertEquals(Set.of(), store.getCallerGroups(null));
    }

    @Test
    void unrelatedCredentialTypesAreNotValidated() {
        StoreHarness harness = storeHarness(
                new StubUserService(),
                passwordService(),
                new SignInAttemptThrottle(),
                "192.0.2.10");

        CredentialValidationResult result = harness.store().validate(new Credential() { });

        assertEquals(CredentialValidationResult.Status.NOT_VALIDATED, result.getStatus());
    }

    private static StoreHarness storeHarness(
            UserService userService,
            PasswordService passwordService,
            SignInAttemptThrottle throttle,
            String remoteAddress) {
        DatabaseIdentityStore store = new DatabaseIdentityStore();
        PasswordValidationState passwordValidationState = new PasswordValidationState();
        setField(store, "userService", userService);
        setField(store, "passwordService", passwordService);
        setField(store, "signInAttemptThrottle", throttle);
        setField(store, "request", request(remoteAddress));
        setField(store, "clientRequestSourceResolver", new ClientRequestSourceResolver(null));
        setField(store, "passwordValidationState", passwordValidationState);
        setField(store, "authenticationAuditService", AuthenticationAuditService.noOperation());
        return new StoreHarness(store, passwordValidationState);
    }

    private static HttpServletRequest request(String remoteAddress) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> method.getName().equals("getRemoteAddr")
                        ? remoteAddress
                        : defaultValue(method.getReturnType()));
    }

    private static UsernamePasswordCredential credential(String username, String password) {
        return new UsernamePasswordCredential(username, password);
    }

    private static ApplicationUser activeUser(
            String username,
            String passwordHash,
            long passwordVersion) {
        ApplicationUser user = new ApplicationUser();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setPasswordHash(passwordHash);
        user.setPasswordVersion(passwordVersion);
        user.setActive(true);
        return user;
    }

    private static String normalizedUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private record StoreHarness(
            DatabaseIdentityStore store,
            PasswordValidationState passwordValidationState) {
    }

    private static final class StubUserService extends UserService {
        private final Map<String, ApplicationUser> users = new HashMap<>();
        private int lookupCount;

        @Override
        public String normalizeUsername(String username) {
            return normalizedUsername(username);
        }

        @Override
        public Optional<ApplicationUser> findActiveByUsername(String username) {
            lookupCount++;
            return Optional.ofNullable(users.get(username))
                    .filter(ApplicationUser::isActive);
        }
    }
}
