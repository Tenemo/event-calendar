package app.invitation;

import java.time.OffsetDateTime;

public record InvitationSummary(
        Long id,
        String invitationToken,
        Long calendarId,
        String calendarName,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
}
