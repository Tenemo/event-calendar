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
    void candidateValidationEnforcesThePersistenceAndRedirectBoundary() {
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
}
