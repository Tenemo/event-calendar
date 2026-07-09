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
    private static final String DUMMY_PASSWORD_HASH =
            "PBKDF2WithHmacSHA256:600000:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=:"
                    + "1CWgpzdZaXuiv+M7nJjALTxRC5d19dsMY6jY4Nm9n0E=";

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
        String password = usernamePasswordCredential.getPasswordAsString();
        return userService.findActiveByUsername(username)
                .map(user -> validatePassword(user, password))
                .orElseGet(() -> validateMissingUserPassword(password));
    }

    private CredentialValidationResult validatePassword(AppUser user, String password) {
        if (passwordService.verifyPassword(password, user.getPasswordHash())) {
            return validUser(user);
        }
        return CredentialValidationResult.INVALID_RESULT;
    }

    private CredentialValidationResult validateMissingUserPassword(String password) {
        passwordService.verifyPassword(password, DUMMY_PASSWORD_HASH);
        return CredentialValidationResult.INVALID_RESULT;
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
