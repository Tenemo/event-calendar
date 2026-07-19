package app.security;

import app.user.ApplicationUser;
import app.user.UserService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

@ApplicationScoped
public class DatabaseIdentityStore implements IdentityStore {
    @Inject
    private UserService userService;

    @Inject
    private PasswordService passwordService;

    @Inject
    private LoginAttemptThrottle loginAttemptThrottle;

    @Inject
    private HttpServletRequest request;

    @Inject
    private ClientRequestSourceResolver clientRequestSourceResolver;

    @Inject
    private PasswordValidationState passwordValidationState;

    @Override
    public CredentialValidationResult validate(Credential credential) {
        passwordValidationState.clear();
        if (!(credential instanceof UsernamePasswordCredential usernamePasswordCredential)) {
            return CredentialValidationResult.NOT_VALIDATED_RESULT;
        }

        String username = userService.normalizeUsername(usernamePasswordCredential.getCaller());
        String password = usernamePasswordCredential.getPasswordAsString();
        String sourceIdentifier = sourceIdentifier();
        synchronized (loginAttemptThrottle.validationLock(sourceIdentifier)) {
            if (!loginAttemptThrottle.isAuthenticationAllowed(username, sourceIdentifier)) {
                return CredentialValidationResult.INVALID_RESULT;
            }

            CredentialValidationResult validationResult = userService.findActiveByUsername(username)
                    .map(user -> validatePassword(user, password))
                    .orElseGet(() -> validateMissingUserPassword(password));
            if (validationResult.getStatus() == CredentialValidationResult.Status.VALID) {
                loginAttemptThrottle.clearUsernameAndSourceFailures(username, sourceIdentifier);
            } else {
                loginAttemptThrottle.recordFailedAuthentication(username, sourceIdentifier);
            }
            return validationResult;
        }
    }

    private CredentialValidationResult validatePassword(ApplicationUser user, String password) {
        String validatedUsername = user.getUsername();
        String validatedPasswordHash = user.getPasswordHash();
        long validatedPasswordVersion = user.getPasswordVersion();
        if (passwordService.verifyPassword(password, validatedPasswordHash)) {
            return validUser(validatedUsername, validatedPasswordVersion);
        }
        return CredentialValidationResult.INVALID_RESULT;
    }

    String sourceIdentifier() {
        return clientRequestSourceResolver.resolve(request);
    }

    private CredentialValidationResult validateMissingUserPassword(String password) {
        passwordService.verifyMissingUserPassword(password);
        return CredentialValidationResult.INVALID_RESULT;
    }

    private CredentialValidationResult validUser(String username, long validatedPasswordVersion) {
        passwordValidationState.recordSuccessfulValidation(username, validatedPasswordVersion);
        return new CredentialValidationResult(username, Set.of("USER"));
    }

    @Override
    public Set<String> getCallerGroups(CredentialValidationResult validationResult) {
        if (validationResult == null || validationResult.getCallerPrincipal() == null) {
            return Set.of();
        }
        if (userService.findActiveByUsername(validationResult.getCallerPrincipal().getName()).isPresent()) {
            return Set.of("USER");
        }
        return Set.of();
    }

    @Override
    public Set<ValidationType> validationTypes() {
        return Set.of(ValidationType.VALIDATE, ValidationType.PROVIDE_GROUPS);
    }

}
