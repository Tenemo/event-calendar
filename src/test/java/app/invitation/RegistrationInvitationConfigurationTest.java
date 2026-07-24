package app.invitation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RegistrationInvitationConfigurationTest {
    private static final String VALID_BOOTSTRAP_INVITATION_TOKEN =
            "6RrWdXJO3P9mQ1fUh8zGkV2nY5cBsA7tEeL0iNxC4_o";

    @Test
    void blankConfigurationDisablesBootstrapAdmission() {
        RegistrationInvitationConfiguration missingConfiguration =
                new RegistrationInvitationConfiguration(null);
        RegistrationInvitationConfiguration blankConfiguration =
                new RegistrationInvitationConfiguration(" \t ");

        assertAll(
                () -> assertDoesNotThrow(missingConfiguration::validate),
                () -> assertDoesNotThrow(blankConfiguration::validate),
                () -> assertFalse(missingConfiguration.matchesBootstrapInvitationToken(
                        VALID_BOOTSTRAP_INVITATION_TOKEN)),
                () -> assertFalse(blankConfiguration.matchesBootstrapInvitationToken(
                        VALID_BOOTSTRAP_INVITATION_TOKEN)));
    }

    @Test
    void validBase64UrlConfigurationMatchesOnlyTheExactToken() {
        RegistrationInvitationConfiguration configuration =
                new RegistrationInvitationConfiguration(
                        " " + VALID_BOOTSTRAP_INVITATION_TOKEN + " ");

        configuration.validate();

        assertAll(
                () -> assertTrue(configuration.matchesBootstrapInvitationToken(
                        VALID_BOOTSTRAP_INVITATION_TOKEN)),
                () -> assertFalse(configuration.matchesBootstrapInvitationToken(
                        VALID_BOOTSTRAP_INVITATION_TOKEN.toLowerCase())),
                () -> assertFalse(configuration.matchesBootstrapInvitationToken(
                        VALID_BOOTSTRAP_INVITATION_TOKEN + "x")));
    }

    @Test
    void rejectsWeakUnsafeAndOversizedConfiguration() {
        assertAll(
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> validate("short-token")),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> validate("a".repeat(42))),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> validate("a".repeat(42) + "&")),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> validate("a".repeat(42) + " ")),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> validate("a".repeat(81))));
    }

    private void validate(String configuredBootstrapInvitationToken) {
        new RegistrationInvitationConfiguration(configuredBootstrapInvitationToken).validate();
    }
}
