package app.fixture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.fixture.CanonicalCalendarFixture.Installation;
import app.fixture.CanonicalCalendarFixture.Scope;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class CanonicalCalendarFixtureTest {
    private static final String PASSWORD_HASH = "algorithm:parameters:salt:hash";

    @Test
    void validEnvironmentSpecificAccountsAndCalendarLinksAreAccepted() {
        assertDoesNotThrow(() -> installation(
                "owner",
                "owner-maya",
                "owner-tomasz",
                "3e508947ceA",
                "ca62893117E",
                "f812ffe4b2I"));
    }

    @Test
    void duplicateOrMalformedIdentitiesAndCalendarLinksAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> installation(
                        "owner",
                        "owner",
                        "owner-tomasz",
                        "3e508947ceA",
                        "ca62893117E",
                        "f812ffe4b2I"));
        assertThrows(
                IllegalArgumentException.class,
                () -> installation(
                        "owner",
                        "owner-maya",
                        "owner-tomasz",
                        "3e508947ceA",
                        "3e508947ceA",
                        "f812ffe4b2I"));
        for (String malformedCalendarLink : new String[] {
            "too-short", "ca62893117!", "ca62893117Aextra", "ca62893117B"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> installation(
                            "owner",
                            "owner-maya",
                            "owner-tomasz",
                            malformedCalendarLink,
                            "ca62893117E",
                            "f812ffe4b2I"));
        }
    }

    @Test
    void diagnosticsRedactPasswordHashesAndBearerLinks() {
        Installation installation = installation(
                "owner",
                "owner-maya",
                "owner-tomasz",
                "3e508947ceA",
                "ca62893117E",
                "f812ffe4b2I");

        assertFalse(installation.toString().contains(PASSWORD_HASH));
        assertFalse(installation.toString().contains("ca62893117E"));
        assertTrue(installation.toString().contains("ownerPasswordHash=redacted"));
        assertTrue(installation.toString().contains("calendarLinkTokens=redacted"));
    }

    private static Installation installation(
            String ownerUsername,
            String mayaUsername,
            String tomaszUsername,
            String friendsCalendarLinkToken,
            String weekendCalendarLinkToken,
            String lisbonCalendarLinkToken) {
        return new Installation(
                Scope.PREVIEW,
                ownerUsername,
                "Fixture owner",
                PASSWORD_HASH,
                false,
                mayaUsername,
                PASSWORD_HASH,
                false,
                tomaszUsername,
                PASSWORD_HASH,
                false,
                friendsCalendarLinkToken,
                weekendCalendarLinkToken,
                lisbonCalendarLinkToken,
                OffsetDateTime.parse("2026-08-03T12:00:00Z"));
    }
}
