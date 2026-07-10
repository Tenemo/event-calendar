package app.invitation;

import app.calendar.Calendar;
import app.membership.CalendarRole;
import app.util.ValidationException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;

@ApplicationScoped
public class InvitationPolicy {
    public void requireValidScope(Calendar calendar, CalendarRole role) {
        if (calendar == null && role == null) {
            return;
        }
        if (calendar != null && role == CalendarRole.EDITOR) {
            return;
        }
        throw new ValidationException("Invitations must be app-only or grant editor access to a calendar.");
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
