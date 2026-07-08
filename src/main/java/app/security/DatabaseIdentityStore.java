package app.security;

import app.user.AppUser;
import app.user.UserService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;
import java.util.Set;

@ApplicationScoped
public class DatabaseIdentityStore implements IdentityStore {
    @Inject
    private UserService userService;

    @Inject
    private PasswordService passwordService;

    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (!(credential instanceof UsernamePasswordCredential usernamePasswordCredential)) {
            return CredentialValidationResult.NOT_VALIDATED_RESULT;
        }

        String username = userService.normalizeUsername(usernamePasswordCredential.getCaller());
        return userService.findActiveByUsername(username)
                .filter(user -> passwordService.verifyPassword(usernamePasswordCredential.getPasswordAsString(), user.getPasswordHash()))
                .map(this::validUser)
                .orElse(CredentialValidationResult.INVALID_RESULT);
    }

    private CredentialValidationResult validUser(AppUser user) {
        return new CredentialValidationResult(user.getUsername(), Set.of("USER"));
    }

    @Override
    public Set<String> getCallerGroups(CredentialValidationResult validationResult) {
        return Set.of("USER");
    }

    @Override
    public Set<ValidationType> validationTypes() {
        return Set.of(ValidationType.VALIDATE, ValidationType.PROVIDE_GROUPS);
    }
}
