package app.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class InvitationViewTest {
    @Test
    void timestampLabelsUseEnglishAndDisplayTheInstantInUtc() {
        assertEquals(
                "Jul 28, 2026 at 14:30 UTC",
                InvitationView.formatTimestamp(OffsetDateTime.parse("2026-07-28T16:30:00+02:00")));
    }
}
