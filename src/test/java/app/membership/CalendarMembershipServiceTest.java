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
        CalendarMembership inactiveAdminMembership = membership(calendar, user, CalendarRole.ADMIN, false);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .singleResult("where calendarMembership.calendar.id", inactiveAdminMembership);
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        CalendarMembership member = membershipService
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
        CalendarMembership activeAdminMembership = membership(calendar, user, CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .singleResult("where calendarMembership.calendar.id", activeAdminMembership);
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        CalendarMembership member = membershipService
                .grantMembershipFromAcceptedInvitation(calendar, invitationCreator, user, CalendarRole.EDITOR)
                .orElseThrow();

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
                        activeUser(99L),
                        activeUser(100L),
                        CalendarRole.ADMIN));
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
        CalendarMembership actorMembership = membership(calendar, actingUser, CalendarRole.ADMIN, true);
        CalendarMembership targetMembership = membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMembership calendarMembership", List.of(actorMembership, targetMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AllowingAccessService());
        setField(membershipService, "auditService", new NoOperationAuditService());

        membershipService.changeMemberRole(actingUser, calendar.getId(), targetMembership.getUser().getId(), CalendarRole.EDITOR);

        assertAll(
                () -> assertTrue(
                        entityManagerStub.lockedQueryTexts().stream()
                                .anyMatch(queryText -> queryText.contains("from CalendarMembership")
                                        && queryText.contains("calendarMembership.calendar.id")),
                        "Membership role changes should lock the calendar membership rows before checking last-admin state."),
                () -> assertEquals(CalendarRole.EDITOR, targetMembership.getRole()));
    }

    @Test
    void soleActiveAdminCannotBeDemotedOrDisabled() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(100L);
        CalendarMembership soleAdminMembership =
                membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMembership calendarMembership", List.of(soleAdminMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);

        ValidationException demotionException = assertThrows(
                ValidationException.class,
                () -> membershipService.changeMemberRole(
                        actingUser,
                        calendar.getId(),
                        soleAdminMembership.getUser().getId(),
                        CalendarRole.EDITOR));
        ValidationException disableException = assertThrows(
                ValidationException.class,
                () -> membershipService.disableMembership(
                        actingUser,
                        calendar.getId(),
                        soleAdminMembership.getUser().getId()));

        assertAll(
                () -> assertEquals(
                        "A calendar must keep at least one active admin.",
                        demotionException.getMessage()),
                () -> assertEquals(
                        "A calendar must keep at least one active admin.",
                        disableException.getMessage()),
                () -> assertEquals(CalendarRole.ADMIN, soleAdminMembership.getRole()),
                () -> assertTrue(soleAdminMembership.isActive()));
    }

    @Test
    void inactiveMembershipRequiresAnExplicitReactivationOperation() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(100L);
        CalendarMembership actorMembership = membership(calendar, actingUser, CalendarRole.ADMIN, true);
        CalendarMembership inactiveMembership = membership(calendar, activeUser(101L), CalendarRole.EDITOR, false);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMembership calendarMembership", List.of(actorMembership, inactiveMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "auditService", new NoOperationAuditService());

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> membershipService.changeMemberRole(
                        actingUser,
                        calendar.getId(),
                        inactiveMembership.getUser().getId(),
                        CalendarRole.ADMIN));

        CalendarMembership reactivatedMembership = membershipService.reactivateMembership(
                actingUser,
                calendar.getId(),
                inactiveMembership.getUser().getId(),
                CalendarRole.ADMIN);

        assertAll(
                () -> assertEquals(
                        "Inactive calendar membership must be reactivated explicitly.",
                        exception.getMessage()),
                () -> assertEquals(inactiveMembership, reactivatedMembership),
                () -> assertEquals(CalendarRole.ADMIN, reactivatedMembership.getRole()),
                () -> assertTrue(reactivatedMembership.isActive()),
                () -> assertNotNull(reactivatedMembership.getUpdatedAt()));
    }

    @Test
    void adminCannotDemoteOrRemoveThemselvesEvenWhenAnotherAdminExists() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(100L);
        CalendarMembership actorMembership = membership(calendar, actingUser, CalendarRole.ADMIN, true);
        CalendarMembership otherAdminMembership = membership(calendar, activeUser(101L), CalendarRole.ADMIN, true);
        OffsetDateTime originalUpdatedAt = actorMembership.getUpdatedAt();
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMembership calendarMembership", List.of(actorMembership, otherAdminMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AllowingAccessService());

        ValidationException roleChangeException = assertThrows(
                ValidationException.class,
                () -> membershipService.changeMemberRole(actingUser, calendar.getId(), actingUser.getId(), CalendarRole.EDITOR));
        ValidationException removalException = assertThrows(
                ValidationException.class,
                () -> membershipService.disableMembership(actingUser, calendar.getId(), actingUser.getId()));
        assertAll(
                () -> assertEquals("You cannot change your own admin role.", roleChangeException.getMessage()),
                () -> assertEquals("You cannot remove your own admin access.", removalException.getMessage()),
                () -> assertEquals(CalendarRole.ADMIN, actorMembership.getRole()),
                () -> assertTrue(actorMembership.isActive()),
                () -> assertEquals(originalUpdatedAt, actorMembership.getUpdatedAt()));
    }

    @Test
    void membershipIsNotDisabledWhenAdministrationIsRevokedWhileWaitingForTheCalendarLock() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(100L);
        CalendarMembership actorMembership = membership(calendar, actingUser, CalendarRole.ADMIN, true);
        CalendarMembership targetMembership = membership(calendar, activeUser(101L), CalendarRole.EDITOR, true);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("from Calendar calendarEntity", calendar)
                .resultList("from CalendarMembership calendarMembership", List.of(actorMembership, targetMembership));
        CalendarMembershipService membershipService = membershipService(entityManagerStub);
        setField(membershipService, "calendarAccessService", new AdministrationRevokedAfterInitialCheckAccessService());

        AuthorizationException exception = assertThrows(
                AuthorizationException.class,
                () -> membershipService.disableMembership(
                        actingUser,
                        calendar.getId(),
                        targetMembership.getUser().getId()));

        assertAll(
                () -> assertEquals("Admin access is required.", exception.getMessage()),
                () -> assertTrue(targetMembership.isActive()),
                () -> assertEquals(
                        OffsetDateTime.parse("2026-07-08T12:00:00Z"),
                        targetMembership.getUpdatedAt()));
    }

    private static CalendarMembershipService membershipService(EntityManagerStub entityManagerStub) {
        CalendarMembershipService membershipService = new CalendarMembershipService();
        setField(membershipService, "entityManager", entityManagerStub.entityManager());
        setField(membershipService, "calendarAccessService", new AllowingAccessService());
        return membershipService;
    }

    private static Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setCalendarLinkToken("Abc_123-xY0");
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

    private static CalendarMembership membership(Calendar calendar, ApplicationUser user, CalendarRole role, boolean active) {
        CalendarMembership member = new CalendarMembership();
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

    private static final class AdministrationRevokedAfterInitialCheckAccessService extends CalendarAccessService {
        private int administrationChecks;

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
            administrationChecks++;
            if (administrationChecks > 1) {
                throw new AuthorizationException("Admin access is required.");
            }
        }
    }

    private static final class NoOperationAuditService extends AuditService {
        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
        }
    }
}
