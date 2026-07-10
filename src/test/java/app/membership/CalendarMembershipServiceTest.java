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
import app.user.AppUser;
import app.util.AuthorizationException;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CalendarMembershipServiceTest {
    @Test
    void acceptedInvitationReactivatesInactiveMembershipAtInvitationRole() {
        Calendar calendar = activeCalendar(200L);
        AppUser user = activeUser(100L);
        CalendarMember inactiveAdminMembership = membership(calendar, user, CalendarRole.ADMIN, false);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where calendarMember.calendar.id", inactiveAdminMembership);
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        CalendarMember member = membershipService.grantMembershipFromAcceptedInvitation(calendar, user, CalendarRole.VIEWER);

        assertAll(
                () -> assertEquals(inactiveAdminMembership, member),
                () -> assertEquals(CalendarRole.VIEWER, member.getRole()),
                () -> assertTrue(member.isActive()),
                () -> assertNotNull(member.getUpdatedAt()));
    }

    @Test
    void acceptedInvitationPreservesActiveStrongerMembership() {
        Calendar calendar = activeCalendar(200L);
        AppUser user = activeUser(100L);
        CalendarMember activeAdminMembership = membership(calendar, user, CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where calendarMember.calendar.id", activeAdminMembership);
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        CalendarMember member = membershipService.grantMembershipFromAcceptedInvitation(calendar, user, CalendarRole.VIEWER);

        assertAll(
                () -> assertEquals(activeAdminMembership, member),
                () -> assertEquals(CalendarRole.ADMIN, member.getRole()),
                () -> assertTrue(member.isActive()),
                () -> assertNotNull(member.getUpdatedAt()));
    }

    @Test
    void acceptedInvitationRejectsAdminRole() {
        CalendarMembershipService membershipService = membershipService(entityManagerStub());

        assertThrows(
                ValidationException.class,
                () -> membershipService.grantMembershipFromAcceptedInvitation(
                        activeCalendar(200L),
                        activeUser(100L),
                        CalendarRole.ADMIN));
    }

    @Test
    void roleChangesLockCalendarMembershipsBeforeChangingAdminState() {
        Calendar calendar = activeCalendar(200L);
        AppUser actor = activeUser(100L);
        CalendarMember actorMembership = membership(calendar, actor, CalendarRole.ADMIN, true);
        CalendarMember targetMembership = membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .resultList("from CalendarMember calendarMember", List.of(actorMembership, targetMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AllowingAccessService());
        setField(membershipService, "auditService", new NoopAuditService());

        membershipService.changeMemberRole(actor, calendar.getId(), targetMembership.getUser().getId(), CalendarRole.EDITOR);

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
        AppUser actor = activeUser(100L);
        CalendarMember actorMembership = membership(calendar, actor, CalendarRole.ADMIN, true);
        CalendarMember otherAdminMembership = membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        OffsetDateTime originalUpdatedAt = actorMembership.getUpdatedAt();
        EntityManagerStub entityManagerStub = entityManagerStub()
                .resultList("from CalendarMember calendarMember", List.of(actorMembership, otherAdminMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AllowingAccessService());

        ValidationException roleChangeException = assertThrows(
                ValidationException.class,
                () -> membershipService.changeMemberRole(actor, calendar.getId(), actor.getId(), CalendarRole.EDITOR));
        ValidationException removalException = assertThrows(
                ValidationException.class,
                () -> membershipService.disableMember(actor, calendar.getId(), actor.getId()));
        ValidationException roleGrantException = assertThrows(
                ValidationException.class,
                () -> membershipService.addMemberByRole(actor, calendar.getId(), actor.getId(), CalendarRole.VIEWER));

        assertAll(
                () -> assertEquals("You cannot change your own admin role.", roleChangeException.getMessage()),
                () -> assertEquals("You cannot remove your own admin access.", removalException.getMessage()),
                () -> assertEquals("You cannot change your own admin role.", roleGrantException.getMessage()),
                () -> assertEquals(CalendarRole.ADMIN, actorMembership.getRole()),
                () -> assertTrue(actorMembership.isActive()),
                () -> assertEquals(originalUpdatedAt, actorMembership.getUpdatedAt()));
    }

    @Test
    void memberGrantRequiresAdminAccessBeforeChangingMemberships() {
        CalendarMembershipService membershipService = new CalendarMembershipService();
        setField(membershipService, "calendarAccessService", new RejectingAccessService());

        assertThrows(
                AuthorizationException.class,
                () -> membershipService.addMemberByRole(activeUser(100L), 200L, 101L, CalendarRole.EDITOR));
    }

    private static CalendarMembershipService membershipService(EntityManagerStub entityManagerStub) {
        CalendarMembershipService membershipService = new CalendarMembershipService();
        setField(membershipService, "entityManager", entityManagerStub.entityManager());
        setField(membershipService, "calendarRolePolicy", new CalendarRolePolicy());
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

    private static AppUser activeUser(Long id) {
        AppUser user = new AppUser();
        setEntityId(user, id);
        user.setUsername("user-" + id);
        user.setDisplayName("User " + id);
        user.setActive(true);
        return user;
    }

    private static CalendarMember membership(Calendar calendar, AppUser user, CalendarRole role, boolean active) {
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
        public void requireCanAdminister(AppUser user, Long calendarId) {
        }
    }

    private static final class RejectingAccessService extends CalendarAccessService {
        @Override
        public void requireCanAdminister(AppUser user, Long calendarId) {
            throw new AuthorizationException("Admin access is required.");
        }
    }

    private static final class NoopAuditService extends AuditService {
        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
