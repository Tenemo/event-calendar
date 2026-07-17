package app.endtoend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class BearerSecretRedactorTest {
    private final BearerSecretRedactor redactor = new BearerSecretRedactor();

    @Test
    void redactsRegistrationAndLegacyInvitationQueryParametersWithoutLosingRouteContext() {
        String diagnostic = "POST https://calendar.test/register?token=invitation-secret "
                + "then /login?invite=legacy-secret&source=test";

        assertEquals(
                "POST https://calendar.test/register?token=[redacted] "
                        + "then /login?invite=[redacted]&source=test",
                redactor.redact(diagnostic));
    }

    @Test
    void redactsCanonicalCalendarPathsOnlyAtTokenBoundaries() {
        String diagnostic = "failed https://calendar.test/AAAAAAAAAAE?view=month; "
                + "invalid /AAAAAAAAAAB and longer /AAAAAAAAAAEextra stay visible";

        assertEquals(
                "failed https://calendar.test/[calendar-token]?view=month; "
                        + "invalid /AAAAAAAAAAB and longer /AAAAAAAAAAEextra stay visible",
                redactor.redact(diagnostic));
    }

    @Test
    void rememberedGeneratedInputValuesAreRedactedWhenTheyAppearWithoutUrlContext() {
        redactor.rememberBearerValue(
                "https://calendar.test/register?token=generated-input-secret");

        assertEquals(
                "generated value [redacted] appeared in a browser message",
                redactor.redact(
                        "generated value generated-input-secret appeared in a browser message"));
    }

    @Test
    void urlRedactionPreservesOriginAndPathWhileRemovingBearerDataAndQueries() {
        redactor.rememberBearerValue("https://calendar.test/0123456789E");

        assertEquals(
                "https://calendar.test/[redacted]?[redacted]",
                redactor.redactUrl("https://calendar.test/0123456789E?token=secret"));
    }

    @Test
    void unrelatedTextAndNullValuesRemainUsable() {
        assertEquals("ordinary diagnostic text", redactor.redact("ordinary diagnostic text"));
        assertNull(redactor.redact(null));
        assertEquals("[unparseable URL]", redactor.redactUrl(null));
    }
}
