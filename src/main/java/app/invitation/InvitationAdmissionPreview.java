package app.invitation;

import java.time.OffsetDateTime;

public final class InvitationAdmissionPreview {
    private static final InvitationAdmissionPreview UNAVAILABLE =
            new InvitationAdmissionPreview(false, false, null, null);

    private final boolean available;
    private final boolean calendarEditorInvitation;
    private final String calendarName;
    private final OffsetDateTime expiresAt;

    private InvitationAdmissionPreview(
            boolean available,
            boolean calendarEditorInvitation,
            String calendarName,
            OffsetDateTime expiresAt) {
        this.available = available;
        this.calendarEditorInvitation = calendarEditorInvitation;
        this.calendarName = calendarName;
        this.expiresAt = expiresAt;
    }

    public static InvitationAdmissionPreview unavailable() {
        return UNAVAILABLE;
    }

    public static InvitationAdmissionPreview registration(OffsetDateTime expiresAt) {
        return new InvitationAdmissionPreview(true, false, null, expiresAt);
    }

    public static InvitationAdmissionPreview calendarEditor(
            String calendarName,
            OffsetDateTime expiresAt) {
        return new InvitationAdmissionPreview(true, true, calendarName, expiresAt);
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
