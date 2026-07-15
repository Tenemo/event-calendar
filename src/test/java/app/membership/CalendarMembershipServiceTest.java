package app.membership;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.ApplicationUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarMembershipServiceTest {
    @Test
    void acceptedInvitationReactivatesInactiveMembershipAtInvitationRole() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser user = activeUser(100L);
        ApplicationUser invitationCreator = activeUser(99L);
        CalendarMember inactiveAdminMembership = membership(calendar, user, CalendarRole.ADMIN, false);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .singleResult("where calendarMember.calendar.id", inactiveAdminMembership);
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        CalendarMember member = membershipService
                .grantMembershipFromAcceptedInvitation(calendar, invitationCreator, user, CalendarRole.EDITOR)
                .orElseThrow();

        assertAll(
                () -> assertEquals(inactiveAdminMembership, member),
                () -> assertEquals(CalendarRole.EDITOR, member.getRole()),
                () -> assertTrue(member.isActive()),
                () -> assertNotNull(member.getUpdatedAt()),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("from Calendar calendarEntity"))));
    }

    @Test
    void acceptedInvitationPreservesActiveStrongerMembership() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser user = activeUser(100L);
        ApplicationUser invitationCreator = activeUser(99L);
        CalendarMember activeAdminMembership = membership(calendar, user, CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .singleResult("where calendarMember.calendar.id", activeAdminMembership);
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        CalendarMember member = membershipService
                .grantMembershipFromAcceptedInvitation(calendar, invitationCreator, user, CalendarRole.EDITOR)
                .orElseThrow();

        assertAll(
                () -> assertEquals(activeAdminMembership, member),
                () -> assertEquals(CalendarRole.ADMIN, member.getRole()),
                () -> assertTrue(member.isActive()),
                () -> assertNotNull(member.getUpdatedAt()));
    }

    @Test
    void acceptedInvitationRejectsViewerAndAdminRoles() {
        CalendarMembershipService membershipService = membershipService(entityManagerStub());

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> membershipService.grantMembershipFromAcceptedInvitation(
                                activeCalendar(200L),
                                activeUser(99L),
                                activeUser(100L),
                                CalendarRole.VIEWER)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> membershipService.grantMembershipFromAcceptedInvitation(
                                activeCalendar(200L),
                                activeUser(99L),
                                activeUser(100L),
                                CalendarRole.ADMIN)));
    }

    @Test
    void acceptedInvitationDoesNotReactivateItsCreatorAfterEditAccessWasRemoved() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser removedEditor = activeUser(100L);
        CalendarMembershipService membershipService = membershipService(
                entityManagerStub().singleResult("from Calendar calendarEntity", calendar));
        setField(membershipService, "calendarAccessService", new DenyingEditAccessService());

        assertTrue(membershipService
                .grantMembershipFromAcceptedInvitation(
                        calendar,
                        removedEditor,
                        removedEditor,
                        CalendarRole.EDITOR)
                .isEmpty());
    }

    @Test
    void roleChangesLockCalendarMembershipsBeforeChangingAdminState() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(100L);
        CalendarMember actorMembership = membership(calendar, actingUser, CalendarRole.ADMIN, true);
        CalendarMember targetMembership = membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMember calendarMember", List.of(actorMembership, targetMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AllowingAccessService());
        setField(membershipService, "auditService", new NoopAuditService());

        membershipService.changeMemberRole(actingUser, calendar.getId(), targetMembership.getUser().getId(), CalendarRole.EDITOR);

        assertAll(
                () -> assertTrue(
                        entityManagerStub.lockedQueryTexts().stream()
                                .anyMatch(queryText -> queryText.contains("from CalendarMember")
                                        && queryText.contains("calendarMember.calendar.id")),
                        "Membership role changes should lock the calendar membership rows before checking last-admin state."),
                () -> assertEquals(CalendarRole.EDITOR, targetMembership.getRole()));
    }

    @Test
    void adminCannotDemoteOrRemoveThemselvesEvenWhenAnotherAdminExists() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(100L);
        CalendarMember actorMembership = membership(calendar, actingUser, CalendarRole.ADMIN, true);
        CalendarMember otherAdminMembership = membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        OffsetDateTime originalUpdatedAt = actorMembership.getUpdatedAt();
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMember calendarMember", List.of(actorMembership, otherAdminMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AllowingAccessService());

        ValidationException roleChangeException = assertThrows(
                ValidationException.class,
                () -> membershipService.changeMemberRole(actingUser, calendar.getId(), actingUser.getId(), CalendarRole.EDITOR));
        ValidationException removalException = assertThrows(
                ValidationException.class,
                () -> membershipService.disableMember(actingUser, calendar.getId(), actingUser.getId()));
        assertAll(
                () -> assertEquals("You cannot change your own admin role.", roleChangeException.getMessage()),
                () -> assertEquals("You cannot remove your own admin access.", removalException.getMessage()),
                () -> assertEquals(CalendarRole.ADMIN, actorMembership.getRole()),
                () -> assertTrue(actorMembership.isActive()),
                () -> assertEquals(originalUpdatedAt, actorMembership.getUpdatedAt()));
    }

    private static CalendarMembershipService membershipService(EntityManagerStub entityManagerStub) {
        CalendarMembershipService membershipService = new CalendarMembershipService();
        setField(membershipService, "entityManager", entityManagerStub.entityManager());
        setField(membershipService, "calendarAccessService", new AllowingAccessService());
        setField(membershipService, "calendarMembershipPolicy", new CalendarMembershipPolicy());
        return membershipService;
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

    private static ApplicationUser activeUser(Long id) {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, id);
        user.setUsername("user-" + id);
        user.setDisplayName("User " + id);
        user.setActive(true);
        return user;
    }

    private static CalendarMember membership(Calendar calendar, ApplicationUser user, CalendarRole role, boolean active) {
        CalendarMember member = new CalendarMember();
        member.setCalendar(calendar);
        member.setUser(user);
        member.setRole(role);
        member.setActive(active);
        member.setCreatedAt(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        member.setUpdatedAt(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        return member;
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
        }

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
        }
    }

    private static final class DenyingEditAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            throw new AuthorizationException("Editor access is required.");
        }
    }

    private static final class NoopAuditService extends AuditService {
        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
