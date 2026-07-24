package app.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.security.enterprise.CallerPrincipal;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class PasswordValidationStateTest {
    @Test
    void consumesTheValidatedVersionExactlyOnceForTheMatchingAuthenticatedUser() {
        PasswordValidationState validationState = new PasswordValidationState();
        validationState.recordSuccessfulValidation("piotr", 8);

        OptionalLong consumedVersion = validationState.consumeValidatedPasswordVersion(
                new CallerPrincipal("piotr"));

        assertAll(
                () -> assertEquals(8, consumedVersion.orElseThrow()),
                () -> assertTrue(validationState.consumeValidatedPasswordVersion(
                                new CallerPrincipal("piotr"))
                        .isEmpty()));
    }

    @Test
    void mismatchedOrMissingPrincipalsCannotConsumeOrLeaveReusableValidation() {
        PasswordValidationState mismatchedState = new PasswordValidationState();
        mismatchedState.recordSuccessfulValidation("piotr", 8);
        PasswordValidationState missingPrincipalState = new PasswordValidationState();
        missingPrincipalState.recordSuccessfulValidation("piotr", 8);

        assertAll(
                () -> assertTrue(mismatchedState.consumeValidatedPasswordVersion(
                                new CallerPrincipal("somebody-else"))
                        .isEmpty()),
                () -> assertTrue(mismatchedState.consumeValidatedPasswordVersion(
                                new CallerPrincipal("piotr"))
                        .isEmpty()),
                () -> assertTrue(missingPrincipalState.consumeValidatedPasswordVersion(null).isEmpty()),
                () -> assertTrue(missingPrincipalState.consumeValidatedPasswordVersion(
                                new CallerPrincipal("piotr"))
                        .isEmpty()));
    }
}
