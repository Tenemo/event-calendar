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
    private SignInAttemptThrottle signInAttemptThrottle;

    @Inject
    private HttpServletRequest request;

    @Inject
    private ClientRequestSourceResolver clientRequestSourceResolver;

    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (!(credential instanceof UsernamePasswordCredential submittedCredential)) {
            return CredentialValidationResult.NOT_VALIDATED_RESULT;
        }

        String username = userService.normalizeUsername(submittedCredential.getCaller());
        String password = submittedCredential.getPasswordAsString();
        String source = clientRequestSourceResolver.resolve(request);
        if (!signInAttemptThrottle.reserveAuthenticationAttempt(username, source)) {
            return CredentialValidationResult.INVALID_RESULT;
        }

        try {
            CredentialValidationResult result = userService.findByUsername(username)
                    .map(user -> validatePassword(user, password))
                    .orElseGet(() -> validateMissingUserPassword(password));
            if (result.getStatus() == CredentialValidationResult.Status.VALID) {
                signInAttemptThrottle.releaseSuccessfulAttempt(username, source);
            }
            return result;
        } catch (RuntimeException exception) {
            signInAttemptThrottle.withdrawAuthenticationAttempt(username, source);
            throw exception;
        }
    }

    private CredentialValidationResult validatePassword(ApplicationUser user, String password) {
        if (!passwordService.verifyPassword(password, user.getPasswordHash())) {
            return CredentialValidationResult.INVALID_RESULT;
        }
        return new CredentialValidationResult(user.getUsername(), Set.of("USER"));
    }

    private CredentialValidationResult validateMissingUserPassword(String password) {
        passwordService.verifyMissingUserPassword(password);
        return CredentialValidationResult.INVALID_RESULT;
    }

    @Override
    public Set<String> getCallerGroups(CredentialValidationResult validationResult) {
        if (validationResult == null || validationResult.getCallerPrincipal() == null) {
            return Set.of();
        }
        return userService.findByUsername(validationResult.getCallerPrincipal().getName()).isPresent()
                ? Set.of("USER")
                : Set.of();
    }

    @Override
    public Set<ValidationType> validationTypes() {
        return Set.of(ValidationType.VALIDATE, ValidationType.PROVIDE_GROUPS);
    }
}
