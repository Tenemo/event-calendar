package app.security;

import jakarta.enterprise.context.RequestScoped;
import java.security.Principal;
import java.util.Objects;
import java.util.OptionalLong;

@RequestScoped
public class PasswordValidationState {
    private String validatedUsername;
    private long validatedPasswordVersion;
    private boolean successfulValidationRecorded;

    public void clear() {
        validatedUsername = null;
        validatedPasswordVersion = 0;
        successfulValidationRecorded = false;
    }

    public void recordSuccessfulValidation(String username, long passwordVersion) {
        validatedUsername = Objects.requireNonNull(username);
        validatedPasswordVersion = passwordVersion;
        successfulValidationRecorded = true;
    }

    public OptionalLong consumeValidatedPasswordVersion(Principal authenticatedPrincipal) {
        try {
            if (!successfulValidationRecorded
                    || authenticatedPrincipal == null
                    || !validatedUsername.equals(authenticatedPrincipal.getName())) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(validatedPasswordVersion);
        } finally {
            clear();
        }
    }
}
