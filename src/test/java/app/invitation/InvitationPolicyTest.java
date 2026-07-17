package app.invitation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.calendar.Calendar;
import app.membership.CalendarRole;
import app.testsupport.ServiceTestSupport;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

final class InvitationPolicyTest {
    private final InvitationPolicy invitationPolicy = new InvitationPolicy();

    @Test
    void allowsOnlyRegistrationOrCalendarEditorScopes() {
        Calendar calendar = new Calendar();
        ServiceTestSupport.setEntityId(calendar, 1L);

        assertAll(
                () -> assertDoesNotThrow(() -> invitationPolicy.requireValidScope(null, null)),
                () -> assertDoesNotThrow(() -> invitationPolicy.requireValidScope(calendar, CalendarRole.EDITOR)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(calendar, CalendarRole.ADMIN)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(calendar, null)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireValidScope(null, CalendarRole.EDITOR)));
    }

    @Test
    void rejectsRevokedAcceptedOrExpiredInvitations() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertAll(
                () -> assertDoesNotThrow(() -> invitationPolicy.requireOpen(null, null, now.plusMinutes(1), now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(null, null, null, now)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> invitationPolicy.requireOpen(now.minusHours(1), null, now.plusMinutes(1), now)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> invitationPolicy.requireOpen(null, now.minusHours(1), now.plusMinutes(1), now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(null, null, now, now)),
                () -> assertThrows(ValidationException.class, () -> invitationPolicy.requireOpen(null, null, now.minusSeconds(1), now)));
    }

    @Test
    void suppliesTheOnlyAllowedSevenDayExpiration() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-08T12:00:00Z");

        assertAll(
                () -> assertEquals(createdAt.plusDays(7), invitationPolicy.expirationFor(createdAt)),
                () -> assertThrows(IllegalArgumentException.class, () -> invitationPolicy.expirationFor(null)));
    }

    @Test
    void derivesOneStatusForAvailabilityRenderingAndRevocation() {
        OffsetDateTime currentTime = OffsetDateTime.parse("2026-07-10T12:00:00Z");

        assertAll(
                () -> assertEquals(
                        InvitationStatus.AVAILABLE,
                        invitationPolicy.status(null, null, currentTime.plusNanos(1), currentTime)),
                () -> assertEquals(
                        InvitationStatus.EXPIRED,
                        invitationPolicy.status(null, null, currentTime, currentTime)),
                () -> assertEquals(
                        InvitationStatus.REVOKED,
                        invitationPolicy.status(
                                currentTime.minusDays(1),
                                null,
                                currentTime.plusDays(1),
                                currentTime)),
                () -> assertEquals(
                        InvitationStatus.ACCEPTED,
                        invitationPolicy.status(
                                null,
                                currentTime.minusDays(1),
                                currentTime.plusDays(1),
                                currentTime)),
                () -> assertEquals(
                        InvitationStatus.ACCEPTED,
                        invitationPolicy.status(
                                currentTime.minusDays(1),
                                currentTime.minusDays(2),
                                currentTime.minusDays(3),
                                currentTime)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> invitationPolicy.status(null, null, null, currentTime)),
                () -> assertTrue(InvitationStatus.AVAILABLE.isRevocable()),
                () -> assertFalse(InvitationStatus.ACCEPTED.isRevocable()),
                () -> assertFalse(InvitationStatus.REVOKED.isRevocable()),
                () -> assertFalse(InvitationStatus.EXPIRED.isRevocable()));
    }
}
