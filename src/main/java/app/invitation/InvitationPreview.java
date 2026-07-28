package app.invitation;

import java.time.OffsetDateTime;

public final class InvitationPreview {
    private static final InvitationPreview UNAVAILABLE =
            new InvitationPreview(false, false, null, null);

    private final boolean available;
    private final boolean calendarEditorInvitation;
    private final String calendarName;
    private final OffsetDateTime expiresAt;

    private InvitationPreview(
            boolean available,
            boolean calendarEditorInvitation,
            String calendarName,
            OffsetDateTime expiresAt) {
        this.available = available;
        this.calendarEditorInvitation = calendarEditorInvitation;
        this.calendarName = calendarName;
        this.expiresAt = expiresAt;
    }

    public static InvitationPreview unavailable() {
        return UNAVAILABLE;
    }

    public static InvitationPreview registration(OffsetDateTime expiresAt) {
        return new InvitationPreview(true, false, null, expiresAt);
    }

    public static InvitationPreview calendarEditor(
            String calendarName,
            OffsetDateTime expiresAt) {
        return new InvitationPreview(true, true, calendarName, expiresAt);
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isRegistrationInvitation() {
        return available && !calendarEditorInvitation;
    }

    public boolean isCalendarEditorInvitation() {
        return available && calendarEditorInvitation;
    }

    public String getCalendarName() {
        return calendarName;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
