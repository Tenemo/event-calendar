package app.invitation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.calendar.Calendar;
import app.membership.CalendarRole;
import app.testsupport.ServiceTestSupport;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class InvitationPolicyTest {
    private final InvitationPolicy invitationPolicy = new InvitationPolicy();

    @Test
    void allowsOnlyAppOnlyOrCalendarEditorScopes() {
        Calendar calendar = new Calendar();
        ServiceTestSupport.setEntityId(calendar, 1L);

        assertAll(
                () -> assertDoesNotThrow(() -> invitationPolicy.requireValidScope(null, null)),
                () -> assertDoesNotThrow(() -> invitationPolicy.requireValidScope(calendar, CalendarRole.EDITOR)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(calendar, CalendarRole.VIEWER)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(calendar, CalendarRole.ADMIN)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(calendar, null)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(null, CalendarRole.EDITOR)));
    }

    @Test
    void rejectsRevokedAcceptedOrExpiredInvitations() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertAll(
                () -> assertDoesNotThrow(() -> invitationPolicy.requireOpen(null, null, now.plusMinutes(1), now)),
                () -> assertDoesNotThrow(() -> invitationPolicy.requireOpen(null, null, null, now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(now.minusHours(1), null, null, now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(null, now.minusHours(1), null, now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(null, null, now, now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(null, null, now.minusSeconds(1), now)));
    }
}
