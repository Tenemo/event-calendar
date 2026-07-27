package app.invitation;

import java.time.OffsetDateTime;

public record InvitationSummary(
        Long id,
        String invitationToken,
        String calendarName,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
}
