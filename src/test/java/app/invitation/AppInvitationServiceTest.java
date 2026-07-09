package app.invitation;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.setEntityId;
import static app.testsupport.ServiceTestSupport.setField;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.testsupport.ServiceTestSupport.EntityManagerStub;
import app.user.AppUser;
import app.user.RegistrationAdmission;
import app.user.UserService;
import app.util.AuthorizationException;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AppInvitationServiceTest {
    @Test
    void activeUsersCanCreateAppOnlyInvitations() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.inviteToken", 0L);
        RecordingAuditService auditService = new RecordingAuditService();
        AppInvitationService service = service(entityManagerStub, new FixedTokenService("app-token-abcdefghijklmnopqrstuvwxyz"), auditService);
        AppUser actor = activeUser(1L, "piotr");

        AppInvitation invitation = service.createAppInvitation(actor);

        assertAll(
                () -> assertEquals("app-token-abcdefghijklmnopqrstuvwxyz", invitation.getInviteToken()),
                () -> assertNull(invitation.getCalendar()),
                () -> assertNull(invitation.getRole()),
                () -> assertSame(actor, invitation.getCreatedByUser()),
                () -> assertEquals(ZoneOffset.UTC, invitation.getCreatedAt().getOffset()),
                () -> assertEquals("app_invitation", auditService.entityType),
                () -> assertEquals("created", auditService.action),
                () -> assertNull(auditService.calendar),
                () -> assertEquals(1, entityManagerStub.persistedObjects().size()),
                () -> assertEquals(1, entityManagerStub.flushCount()));
    }

    @Test
    void editorsCanCreateCalendarEditorInvitations() {
        Calendar calendar = activeCalendar(200L);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.inviteToken", 0L);
        RecordingAuditService auditService = new RecordingAuditService();
        AppInvitationService service = service(entityManagerStub, new FixedTokenService("editor-token-abcdefghijklmnopqrstuvwxyz"), auditService);
        setField(service, "calendarAccessService", new AllowingAccessService());
        setField(service, "calendarService", new FixedCalendarService(calendar));
        AppUser actor = activeUser(1L, "editor");

        AppInvitation invitation = service.createCalendarEditorInvitation(actor, calendar.getId(), OffsetDateTime.parse("2026-07-15T12:00:00Z"));

        assertAll(
                () -> assertSame(calendar, invitation.getCalendar()),
                () -> assertEquals(CalendarRole.EDITOR, invitation.getRole()),
                () -> assertEquals(OffsetDateTime.parse("2026-07-15T12:00:00Z"), invitation.getExpiresAt()),
                () -> assertEquals(calendar, auditService.calendar),
                () -> assertEquals("app_invitation", auditService.entityType),
                () -> assertEquals("created", auditService.action));
    }

    @Test
    void usersWithoutEditorAccessCannotCreateCalendarEditorInvitations() {
        AppInvitationService service = service(
                entityManagerStub(),
                new FixedTokenService("editor-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());
        setField(service, "calendarAccessService", new DenyingAccessService());

        assertThrows(
                AuthorizationException.class,
                () -> service.createCalendarEditorInvitation(activeUser(1L, "viewer"), 200L, null));
    }

    @Test
    void appOnlyInvitationRegistersWithoutGrantingCalendarMembership() {
        AppInvitation invitation = appOnlyInvitation(activeUser(1L, "creator"));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.inviteToken", invitation);
        RecordingMembershipService membershipService = new RecordingMembershipService();
        AppInvitationService service = service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        setField(service, "calendarMembershipService", membershipService);
        AppUser newUser = activeUser(2L, "friend");

        RegistrationAdmission admission = service.requireAdmission(" " + invitation.getInviteToken() + " ");
        service.acceptAdmission(admission, newUser);

        assertAll(
                () -> assertFalse(admission.bootstrap()),
                () -> assertSame(invitation, admission.invitation()),
                () -> assertSame(newUser, invitation.getAcceptedByUser()),
                () -> assertEquals(ZoneOffset.UTC, invitation.getAcceptedAt().getOffset()),
                () -> assertFalse(membershipService.membershipGranted),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("where invitation.inviteToken"))));
    }

    @Test
    void editorInvitationRegistersAndGrantsEditorMembership() {
        Calendar calendar = activeCalendar(200L);
        AppInvitation invitation = editorInvitation(activeUser(1L, "creator"), calendar);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.inviteToken", invitation);
        RecordingMembershipService membershipService = new RecordingMembershipService();
        AppInvitationService service = service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        setField(service, "calendarMembershipService", membershipService);
        AppUser newUser = activeUser(2L, "friend");

        RegistrationAdmission admission = service.requireAdmission(invitation.getInviteToken());
        service.acceptAdmission(admission, newUser);

        assertAll(
                () -> assertSame(calendar, membershipService.grantedCalendar),
                () -> assertSame(newUser, membershipService.grantedUser),
                () -> assertEquals(CalendarRole.EDITOR, membershipService.grantedRole),
                () -> assertSame(newUser, invitation.getAcceptedByUser()),
                () -> assertEquals(ZoneOffset.UTC, invitation.getAcceptedAt().getOffset()));
    }

    @Test
    void blankUsedRevokedExpiredMissingAndMalformedInvitationsCannotRegister() {
        AppInvitation usedInvitation = appOnlyInvitation(activeUser(1L, "creator"));
        usedInvitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        AppInvitation revokedInvitation = appOnlyInvitation(activeUser(1L, "creator"));
        revokedInvitation.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        AppInvitation expiredInvitation = appOnlyInvitation(activeUser(1L, "creator"));
        expiredInvitation.setExpiresAt(OffsetDateTime.parse("2026-07-08T11:00:00Z"));
        AppInvitation malformedInvitation = appOnlyInvitation(activeUser(1L, "creator"));
        malformedInvitation.setCalendar(activeCalendar(200L));

        assertAll(
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(null).requireAdmission("   ")),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(usedInvitation).requireAdmission(usedInvitation.getInviteToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(revokedInvitation).requireAdmission(revokedInvitation.getInviteToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(expiredInvitation).requireAdmission(expiredInvitation.getInviteToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(malformedInvitation).requireAdmission(malformedInvitation.getInviteToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForMissingInvitation().requireAdmission("missing-token")));
    }

    @Test
    void bootstrapTokenCreatesOnlyTheFirstUser() {
        AppInvitationService serviceWithoutUsers = serviceForMissingInvitation(false, "bootstrap-token");
        AppInvitationService serviceWithUsers = serviceForMissingInvitation(true, "bootstrap-token");

        RegistrationAdmission admission = serviceWithoutUsers.requireAdmission(" bootstrap-token ");

        assertAll(
                () -> assertTrue(admission.bootstrap()),
                () -> assertNull(admission.invitation()),
                () -> assertThrows(ValidationException.class, () -> serviceWithUsers.requireAdmission("bootstrap-token")),
                () -> assertThrows(ValidationException.class, () -> serviceWithoutUsers.requireAdmission("wrong-token")));
    }

    @Test
    void creatorsCanRevokeUnusedInvitationsButNotUsedOrForeignInvitations() {
        AppUser creator = activeUser(1L, "creator");
        AppInvitation unusedInvitation = appOnlyInvitation(creator);
        EntityManagerStub unusedInvitationEntityManagerStub = entityManagerStub()
                .singleResult("where invitation.id", unusedInvitation);
        AppInvitationService unusedInvitationService = service(unusedInvitationEntityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());

        unusedInvitationService.revokeInvitation(creator, 1L);

        AppInvitation usedInvitation = appOnlyInvitation(creator);
        usedInvitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        AppInvitationService usedInvitationService = service(
                entityManagerStub().singleResult("where invitation.id", usedInvitation),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        AppInvitation foreignInvitation = appOnlyInvitation(activeUser(2L, "other"));
        AppInvitationService foreignInvitationService = service(
                entityManagerStub().singleResult("where invitation.id", foreignInvitation),
                new FixedTokenService("unused"),
                new RecordingAuditService());

        assertAll(
                () -> assertEquals(ZoneOffset.UTC, unusedInvitation.getRevokedAt().getOffset()),
                () -> assertTrue(unusedInvitationEntityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("where invitation.id"))),
                () -> assertThrows(ValidationException.class, () -> usedInvitationService.revokeInvitation(creator, 2L)),
                () -> assertThrows(AuthorizationException.class, () -> foreignInvitationService.revokeInvitation(creator, 3L)));
    }

    @Test
    void usersListOnlyTheirRecentInvitations() {
        List<AppInvitation> invitations = List.of(appOnlyInvitation(activeUser(1L, "creator")));
        AppInvitationService service = service(
                entityManagerStub().resultList("from AppInvitation invitation", invitations),
                new FixedTokenService("unused"),
                new RecordingAuditService());

        assertEquals(invitations, service.listInvitations(activeUser(1L, "creator")));
    }

    private AppInvitationService serviceForInvitation(AppInvitation invitation) {
        EntityManagerStub entityManagerStub = entityManagerStub();
        if (invitation == null) {
            return service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        }
        entityManagerStub.singleResult("where invitation.inviteToken", invitation);
        return service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
    }

    private AppInvitationService serviceForMissingInvitation() {
        return serviceForMissingInvitation(false, "");
    }

    private AppInvitationService serviceForMissingInvitation(boolean hasActiveUsers, String bootstrapInviteToken) {
        AppInvitationService service = service(
                entityManagerStub().singleResultNotFound("where invitation.inviteToken"),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        setField(service, "userService", new FixedUserService(hasActiveUsers));
        setField(service, "bootstrapInviteToken", bootstrapInviteToken);
        return service;
    }

    private AppInvitationService service(
            EntityManagerStub entityManagerStub,
            TokenService tokenService,
            AuditService auditService) {
        AppInvitationService service = new AppInvitationService();
        setField(service, "entityManager", entityManagerStub.entityManager());
        setField(service, "calendarAccessService", new AllowingAccessService());
        setField(service, "calendarService", new FixedCalendarService(activeCalendar(200L)));
        setField(service, "calendarMembershipService", new RecordingMembershipService());
        setField(service, "invitationPolicy", new InvitationPolicy());
        setField(service, "tokenService", tokenService);
        setField(service, "userService", new FixedUserService(false));
        setField(service, "auditService", auditService);
        setField(service, "bootstrapInviteToken", "");
        return service;
    }

    private AppInvitation appOnlyInvitation(AppUser creator) {
        AppInvitation invitation = new AppInvitation();
        setEntityId(invitation, 300L);
        invitation.setInviteToken("app-token-abcdefghijklmnopqrstuvwxyz");
        invitation.setCreatedByUser(creator);
        invitation.setCreatedAt(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        return invitation;
    }

    private AppInvitation editorInvitation(AppUser creator, Calendar calendar) {
        AppInvitation invitation = appOnlyInvitation(creator);
        invitation.setCalendar(calendar);
        invitation.setRole(CalendarRole.EDITOR);
        return invitation;
    }

    private Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setPublicToken("public-token-123456789012345678901234567890");
        calendar.setPublicAccessEnabled(true);
        calendar.setActive(true);
        return calendar;
    }

    private AppUser activeUser(Long id, String username) {
        AppUser user = new AppUser();
        setEntityId(user, id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setActive(true);
        return user;
    }

    private static final class RecordingMembershipService extends CalendarMembershipService {
        private boolean membershipGranted;
        private Calendar grantedCalendar;
        private AppUser grantedUser;
        private CalendarRole grantedRole;

        @Override
        public app.membership.CalendarMember grantMembershipFromAcceptedInvitation(Calendar calendar, AppUser user, CalendarRole role) {
            membershipGranted = true;
            grantedCalendar = calendar;
            grantedUser = user;
            grantedRole = role;
            return new app.membership.CalendarMember();
        }
    }

    private static final class RecordingAuditService extends AuditService {
        private Calendar calendar;
        private String entityType;
        private String action;

        @Override
        public void record(AppUser actorUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
            this.calendar = calendar;
            this.entityType = entityType;
            this.action = action;
        }
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(AppUser user, Long calendarId) {
        }
    }

    private static final class DenyingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(AppUser user, Long calendarId) {
            throw new AuthorizationException("Editor access is required.");
        }
    }

    private static final class FixedCalendarService extends CalendarService {
        private final Calendar calendar;

        private FixedCalendarService(Calendar calendar) {
            this.calendar = calendar;
        }

        @Override
        public Calendar requireActiveCalendar(Long calendarId) {
            return calendar;
        }
    }

    private static final class FixedTokenService extends TokenService {
        private final String token;

        private FixedTokenService(String token) {
            this.token = token;
        }

        @Override
        public String generateToken() {
            return token;
        }
    }

    private static final class FixedUserService extends UserService {
        private final boolean hasActiveUsers;

        private FixedUserService(boolean hasActiveUsers) {
            this.hasActiveUsers = hasActiveUsers;
        }

        @Override
        public boolean hasActiveUsers() {
            return hasActiveUsers;
        }
    }
}
