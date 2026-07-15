package app.invitation;

import app.calendar.Calendar;
import app.membership.CalendarRole;
import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.OffsetDateTime;

@ApplicationScoped
public class InvitationPolicy {
    private static final Duration DEFAULT_VALIDITY = Duration.ofDays(7);

    public void requireValidScope(Calendar calendar, CalendarRole role) {
        if (calendar == null && role == null) {
            return;
        }
        if (calendar != null && role == CalendarRole.EDITOR) {
            return;
        }
        throw new ValidationException("Invitations must support registration only or grant editor access to a calendar.");
    }

    public void requireOpen(OffsetDateTime revokedAt, OffsetDateTime acceptedAt, OffsetDateTime expiresAt, OffsetDateTime now) {
        switch (status(revokedAt, acceptedAt, expiresAt, now)) {
            case USED -> throw new ValidationException("Invitation is already accepted.");
            case REVOKED -> throw new ValidationException("Invitation is revoked.");
            case EXPIRED -> throw new ValidationException("Invitation is expired.");
            case AVAILABLE -> {
            }
        }
    }

    public OffsetDateTime resolveExpiration(OffsetDateTime requestedExpiration, OffsetDateTime createdAt) {
        return requestedExpiration == null ? createdAt.plus(DEFAULT_VALIDITY) : requestedExpiration;
    }

    public InvitationStatus status(
            OffsetDateTime revokedAt,
            OffsetDateTime acceptedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime currentTime) {
        if (acceptedAt != null) {
            return InvitationStatus.USED;
        }
        if (revokedAt != null) {
            return InvitationStatus.REVOKED;
        }
        if (expiresAt != null && !expiresAt.isAfter(currentTime)) {
            return InvitationStatus.EXPIRED;
        }
        return InvitationStatus.AVAILABLE;
    }
}
