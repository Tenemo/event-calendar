package app.invitation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class InvitationTokenTest {
    @Test
    void normalizationTrimsOnlyOuterWhitespace() {
        assertAll(
                () -> assertEquals("", InvitationToken.normalize(null)),
                () -> assertEquals("", InvitationToken.normalize("   ")),
                () -> assertEquals(
                        "alpha beta",
                        InvitationToken.normalize("  alpha beta\t")));
    }

    @Test
    void candidateValidationBoundsUntrustedLookupAndRedirectInput() {
        String maximumLengthToken = "a".repeat(InvitationToken.MAXIMUM_LENGTH);

        assertAll(
                () -> assertTrue(InvitationToken.isValidCandidate("app-token_123")),
                () -> assertTrue(InvitationToken.isValidCandidate("alpha beta&gamma")),
                () -> assertTrue(InvitationToken.isValidCandidate(maximumLengthToken)),
                () -> assertFalse(InvitationToken.isValidCandidate(null)),
                () -> assertFalse(InvitationToken.isValidCandidate("")),
                () -> assertFalse(InvitationToken.isValidCandidate("   ")),
                () -> assertFalse(InvitationToken.isValidCandidate(maximumLengthToken + "a")),
                () -> assertFalse(InvitationToken.isValidCandidate("token\\suffix")),
                () -> assertFalse(InvitationToken.isValidCandidate("token\r\nsuffix")),
                () -> assertFalse(InvitationToken.isValidCandidate("token\u0085suffix")));
    }

    @Test
    void bootstrapSecretsUseOnlyAsciiBase64UrlCharactersWithinTheConfiguredBounds() {
        String minimumLengthToken = "A".repeat(40) + "0-_";
        String maximumLengthToken = "z".repeat(InvitationToken.MAXIMUM_LENGTH);

        assertAll(
                () -> assertTrue(InvitationToken.isValidBootstrapSecret(minimumLengthToken)),
                () -> assertTrue(InvitationToken.isValidBootstrapSecret(maximumLengthToken)),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret(null)),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("a".repeat(42))),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("a".repeat(81))),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("é" + "a".repeat(42))),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("٣" + "a".repeat(42))),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("a".repeat(42) + "+")),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("a".repeat(42) + "/")),
                () -> assertFalse(InvitationToken.isValidBootstrapSecret("a".repeat(42) + "=")));
    }
}
