package app.invitation;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.membership.CalendarMember;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.membership.CalendarRolePolicy;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.AppUser;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InvitationServiceTest {
    @Test
    void inviteAcceptanceAssignsTheIntendedCalendarRole() {
        AppUser acceptingUser = activeUser(100L);
        Calendar calendar = activeCalendar(200L);
        CalendarInvitation invitation = openInvitation(calendar, CalendarRole.EDITOR);
        RecordingMembershipService membershipService = new RecordingMembershipService(Optional.empty());

        InvitationService invitationService = invitationService(invitation, membershipService);

        invitationService.acceptInvitation("invite-token", acceptingUser);

        assertAll(
                () -> assertEquals(calendar, membershipService.grantedCalendar),
                () -> assertEquals(acceptingUser, membershipService.grantedUser),
                () -> assertEquals(CalendarRole.EDITOR, membershipService.grantedRole),
                () -> assertEquals(acceptingUser, invitation.getAcceptedByUser()),
                () -> assertNotNull(invitation.getAcceptedAt()));
    }

    @Test
    void inviteAcceptanceDoesNotDowngradeExistingStrongerMembership() {
        AppUser acceptingUser = activeUser(100L);
        Calendar calendar = activeCalendar(200L);
        CalendarInvitation invitation = openInvitation(calendar, CalendarRole.VIEWER);
        CalendarMember existingMembership = new CalendarMember();
        existingMembership.setCalendar(calendar);
        existingMembership.setUser(acceptingUser);
        existingMembership.setRole(CalendarRole.ADMIN);
        existingMembership.setActive(true);
        RecordingMembershipService membershipService = new RecordingMembershipService(Optional.of(existingMembership));

        InvitationService invitationService = invitationService(invitation, membershipService);

        invitationService.acceptInvitation("invite-token", acceptingUser);

        assertAll(
                () -> assertEquals(CalendarRole.ADMIN, existingMembership.getRole()),
                () -> assertEquals(acceptingUser, invitation.getAcceptedByUser()),
                () -> assertNotNull(invitation.getAcceptedAt()));
    }

    @Test
    void inviteAcceptanceRejectsInactiveUsersBeforeMembershipChanges() {
        AppUser inactiveUser = activeUser(100L);
        inactiveUser.setActive(false);
        RecordingMembershipService membershipService = new RecordingMembershipService(Optional.empty());
        InvitationService invitationService = invitationService(openInvitation(activeCalendar(200L), CalendarRole.VIEWER), membershipService);

        assertThrows(ValidationException.class, () -> invitationService.acceptInvitation("invite-token", inactiveUser));
    }

    private static InvitationService invitationService(
            CalendarInvitation invitation,
            RecordingMembershipService membershipService) {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from CalendarInvitation", invitation);
        InvitationService invitationService = new InvitationService();
        setField(invitationService, "entityManager", entityManagerStub.entityManager());
        setField(invitationService, "calendarMembershipService", membershipService);
        setField(invitationService, "calendarRolePolicy", new CalendarRolePolicy());
        setField(invitationService, "invitationPolicy", new InvitationPolicy());
        setField(invitationService, "auditService", new NoopAuditService());
        return invitationService;
    }

    private static CalendarInvitation openInvitation(Calendar calendar, CalendarRole role) {
        CalendarInvitation invitation = new CalendarInvitation();
        setEntityId(invitation, 300L);
        invitation.setCalendar(calendar);
        invitation.setInviteToken("invite-token");
        invitation.setRole(role);
        invitation.setCreatedByUser(activeUser(1L));
        invitation.setCreatedAt(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        return invitation;
    }

    private static Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setPublicToken("public-token-123456789012345678901234567890");
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        return calendar;
    }

    private static AppUser activeUser(Long id) {
        AppUser user = new AppUser();
        setEntityId(user, id);
        user.setUsername("piotr");
        user.setDisplayName("Piotr");
        user.setActive(true);
        return user;
    }

    private static final class RecordingMembershipService extends CalendarMembershipService {
        private final Optional<CalendarMember> existingMembership;
        private Calendar grantedCalendar;
        private AppUser grantedUser;
        private CalendarRole grantedRole;

        private RecordingMembershipService(Optional<CalendarMember> existingMembership) {
            this.existingMembership = existingMembership;
        }

        @Override
        public Optional<CalendarMember> findMembership(Long calendarId, Long userId) {
            return existingMembership;
        }

        @Override
        public CalendarMember grantOrUpdateMembership(Calendar calendar, AppUser user, CalendarRole role) {
            grantedCalendar = calendar;
            grantedUser = user;
            grantedRole = role;
            CalendarMember member = new CalendarMember();
            member.setCalendar(calendar);
            member.setUser(user);
            member.setRole(role);
            member.setActive(true);
            return member;
        }
    }

    private static final class NoopAuditService extends AuditService {
        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
