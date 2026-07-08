package app.invitation;

import app.membership.CalendarRole;
import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;

@ApplicationScoped
public class InvitationPolicy {
    public void requireInvitableRole(CalendarRole role) {
        if (role != CalendarRole.VIEWER && role != CalendarRole.EDITOR) {
            throw new ValidationException("Invitations can only grant viewer or editor access.");
        }
    }

    public void requireOpen(OffsetDateTime revokedAt, OffsetDateTime acceptedAt, OffsetDateTime expiresAt, OffsetDateTime now) {
        if (revokedAt != null) {
            throw new ValidationException("Invitation is revoked.");
        }
        if (acceptedAt != null) {
            throw new ValidationException("Invitation is already accepted.");
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ValidationException("Invitation is expired.");
        }
    }
}
