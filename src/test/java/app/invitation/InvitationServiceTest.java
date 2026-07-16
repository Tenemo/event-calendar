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
import app.testsupport.ServiceTestSupport.QueryPagination;
import app.user.ApplicationUser;
import app.user.RegistrationAdmission;
import app.user.RegistrationBootstrapState;
import app.util.AuthorizationException;
import app.util.ValidationException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InvitationServiceTest {
    @Test
    void activeUsersCanCreateRegistrationInvitations() {
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.invitationToken", 0L);
        RecordingAuditService auditService = new RecordingAuditService();
        InvitationService service = service(entityManagerStub, new FixedTokenService("app-token-abcdefghijklmnopqrstuvwxyz"), auditService);
        ApplicationUser actingUser = activeUser(1L, "piotr");

        Invitation invitation = service.createRegistrationInvitation(actingUser);

        assertAll(
                () -> assertEquals("app-token-abcdefghijklmnopqrstuvwxyz", invitation.getInvitationToken()),
                () -> assertNull(invitation.getCalendar()),
                () -> assertNull(invitation.getRole()),
                () -> assertSame(actingUser, invitation.getCreatedByUser()),
                () -> assertEquals(ZoneOffset.UTC, invitation.getCreatedAt().getOffset()),
                () -> assertEquals(invitation.getCreatedAt().plusDays(7), invitation.getExpiresAt()),
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
                .singleResult("where invitation.invitationToken", 0L);
        RecordingAuditService auditService = new RecordingAuditService();
        InvitationService service = service(entityManagerStub, new FixedTokenService("editor-token-abcdefghijklmnopqrstuvwxyz"), auditService);
        setField(service, "calendarAccessService", new AllowingAccessService());
        setField(service, "calendarService", new FixedCalendarService(calendar));
        ApplicationUser actingUser = activeUser(1L, "editor");

        Invitation invitation = service.createCalendarEditorInvitation(actingUser, calendar.getId(), OffsetDateTime.parse("2026-07-15T12:00:00Z"));

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
        InvitationService service = service(
                entityManagerStub(),
                new FixedTokenService("editor-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());
        setField(service, "calendarAccessService", new DenyingAccessService());

        assertThrows(
                AuthorizationException.class,
                () -> service.createCalendarEditorInvitation(activeUser(1L, "unrelated-user"), 200L, null));
    }

    @Test
    void registrationInvitationRegistersWithoutGrantingCalendarMembership() {
        Invitation invitation = registrationInvitation(activeUser(1L, "creator"));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.invitationToken", invitation)
                .singleResult("where applicationUser.id = :invitationCreatorId", invitation.getCreatedByUser());
        RecordingMembershipService membershipService = new RecordingMembershipService();
        InvitationService service = service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        setField(service, "calendarMembershipService", membershipService);
        ApplicationUser newUser = activeUser(2L, "friend");

        RegistrationAdmission admission = service.requireAdmission(" " + invitation.getInvitationToken() + " ");
        service.acceptAdmission(admission, newUser);

        assertAll(
                () -> assertFalse(admission.bootstrap()),
                () -> assertSame(invitation, admission.invitation()),
                () -> assertSame(newUser, invitation.getAcceptedByUser()),
                () -> assertEquals(ZoneOffset.UTC, invitation.getAcceptedAt().getOffset()),
                () -> assertFalse(membershipService.membershipGranted),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("where invitation.invitationToken"))));
    }

    @Test
    void registrationInvitationIsGenericInvalidWhenItsCreatorIsInactive() {
        ApplicationUser inactiveCreator = activeUser(1L, "inactive-creator");
        inactiveCreator.setActive(false);
        Invitation invitation = registrationInvitation(inactiveCreator);
        InvitationService service = serviceForInvitation(invitation);

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.requireRegistrationAdmission(invitation.getInvitationToken()));

        assertEquals("Invitation is invalid or no longer available.", exception.getMessage());
    }

    @Test
    void editorInvitationRegistersAndGrantsEditorMembership() {
        Calendar calendar = activeCalendar(200L);
        Invitation invitation = editorInvitation(activeUser(1L, "creator"), calendar);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("where invitation.invitationToken", invitation)
                .singleResult("where applicationUser.id = :invitationCreatorId", invitation.getCreatedByUser());
        RecordingMembershipService membershipService = new RecordingMembershipService();
        InvitationService service = service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        setField(service, "calendarMembershipService", membershipService);
        ApplicationUser newUser = activeUser(2L, "friend");

        RegistrationAdmission admission = service.requireAdmission(invitation.getInvitationToken());
        service.acceptAdmission(admission, newUser);

        assertAll(
                () -> assertSame(calendar, membershipService.grantedCalendar),
                () -> assertSame(invitation.getCreatedByUser(), membershipService.grantedCreator),
                () -> assertSame(newUser, membershipService.grantedUser),
                () -> assertEquals(CalendarRole.EDITOR, membershipService.grantedRole),
                () -> assertSame(newUser, invitation.getAcceptedByUser()),
                () -> assertEquals(ZoneOffset.UTC, invitation.getAcceptedAt().getOffset()));
    }

    @Test
    void blankUsedRevokedExpiredMissingAndMalformedInvitationsCannotRegister() {
        Invitation usedInvitation = registrationInvitation(activeUser(1L, "creator"));
        usedInvitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        Invitation revokedInvitation = registrationInvitation(activeUser(1L, "creator"));
        revokedInvitation.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        Invitation expiredInvitation = registrationInvitation(activeUser(1L, "creator"));
        expiredInvitation.setExpiresAt(OffsetDateTime.parse("2026-07-08T11:00:00Z"));
        Invitation malformedInvitation = registrationInvitation(activeUser(1L, "creator"));
        malformedInvitation.setCalendar(activeCalendar(200L));

        assertAll(
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(null).requireAdmission("   ")),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(usedInvitation).requireAdmission(usedInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(revokedInvitation).requireAdmission(revokedInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(expiredInvitation).requireAdmission(expiredInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(malformedInvitation).requireAdmission(malformedInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForMissingInvitation().requireAdmission("missing-token")));
    }

    @Test
    void bootstrapAdmissionIsClaimedOnceAndRejectsDatabasesWhereAnyUserHasEverExisted() {
        RegistrationBootstrapState availableBootstrapState = availableBootstrapState();
        InvitationService serviceWithoutUsers = serviceForMissingInvitation(false, "bootstrap-token", availableBootstrapState);
        InvitationService serviceWithUsers = serviceForMissingInvitation(true, "bootstrap-token", availableBootstrapState());

        RegistrationAdmission admission = serviceWithoutUsers.requireRegistrationAdmission(" bootstrap-token ");

        assertAll(
                () -> assertTrue(admission.bootstrap()),
                () -> assertNull(admission.invitation()),
                () -> assertEquals(ZoneOffset.UTC, availableBootstrapState.getConsumedAt().getOffset()),
                () -> assertThrows(
                        ValidationException.class,
                        () -> serviceWithoutUsers.requireRegistrationAdmission("bootstrap-token")),
                () -> assertThrows(ValidationException.class, () -> serviceWithUsers.requireAdmission("bootstrap-token")),
                () -> assertThrows(ValidationException.class, () -> serviceWithoutUsers.requireAdmission("wrong-token")));
    }

    @Test
    void calendarInvitationIsGenericInvalidWhenItsCreatorIsInactiveOrNoLongerAnEditor() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser inactiveCreator = activeUser(1L, "inactive-creator");
        inactiveCreator.setActive(false);
        Invitation inactiveCreatorInvitation = editorInvitation(inactiveCreator, calendar);
        Invitation removedEditorInvitation = editorInvitation(activeUser(2L, "removed-editor"), calendar);

        InvitationService inactiveCreatorService = serviceForInvitation(inactiveCreatorInvitation);
        InvitationService removedEditorService = serviceForInvitation(removedEditorInvitation);
        setField(removedEditorService, "calendarAccessService", new DenyingAccessService());

        ValidationException inactiveCreatorException = assertThrows(
                ValidationException.class,
                () -> inactiveCreatorService.requireAdmission(inactiveCreatorInvitation.getInvitationToken()));
        ValidationException removedEditorException = assertThrows(
                ValidationException.class,
                () -> removedEditorService.requireAdmission(removedEditorInvitation.getInvitationToken()));

        assertAll(
                () -> assertEquals("Invitation is invalid or no longer available.", inactiveCreatorException.getMessage()),
                () -> assertEquals("Invitation is invalid or no longer available.", removedEditorException.getMessage()));
    }

    @Test
    void creatorPermissionIsRevalidatedAfterAdmissionBeforeMembershipIsGranted() {
        Calendar calendar = activeCalendar(200L);
        Invitation invitation = editorInvitation(activeUser(1L, "creator"), calendar);
        InvitationService service = serviceForInvitation(invitation);
        ApplicationUser acceptingUser = activeUser(2L, "friend");
        RegistrationAdmission admission = service.requireAdmission(invitation.getInvitationToken());
        setField(service, "calendarMembershipService", new RejectingMembershipService());

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.acceptAdmission(admission, acceptingUser));

        assertAll(
                () -> assertEquals("Invitation is invalid or no longer available.", exception.getMessage()),
                () -> assertNull(invitation.getAcceptedAt()),
                () -> assertNull(invitation.getAcceptedByUser()));
    }

    @Test
    void creatorsCanRevokeAvailableInvitationsButNotUsedExpiredOrForeignInvitations() {
        ApplicationUser creator = activeUser(1L, "creator");
        Invitation unusedInvitation = registrationInvitation(creator);
        EntityManagerStub unusedInvitationEntityManagerStub = entityManagerStub()
                .singleResult("where invitation.id", unusedInvitation);
        InvitationService unusedInvitationService = service(unusedInvitationEntityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());

        unusedInvitationService.revokeInvitation(creator, 1L);

        Invitation usedInvitation = registrationInvitation(creator);
        usedInvitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        InvitationService usedInvitationService = service(
                entityManagerStub().singleResult("where invitation.id", usedInvitation),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        Invitation expiredInvitation = registrationInvitation(creator);
        expiredInvitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        InvitationService expiredInvitationService = service(
                entityManagerStub().singleResult("where invitation.id", expiredInvitation),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        Invitation foreignInvitation = registrationInvitation(activeUser(2L, "other"));
        InvitationService foreignInvitationService = service(
                entityManagerStub().singleResult("where invitation.id", foreignInvitation),
                new FixedTokenService("unused"),
                new RecordingAuditService());

        assertAll(
                () -> assertEquals(ZoneOffset.UTC, unusedInvitation.getRevokedAt().getOffset()),
                () -> assertTrue(unusedInvitationEntityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("where invitation.id"))),
                () -> assertTrue(unusedInvitationEntityManagerStub.lockedQueryTexts().stream()
                        .filter(queryText -> queryText.contains("where invitation.id"))
                        .noneMatch(queryText -> queryText.contains("join fetch"))),
                () -> assertThrows(ValidationException.class, () -> usedInvitationService.revokeInvitation(creator, 2L)),
                () -> assertThrows(ValidationException.class, () -> expiredInvitationService.revokeInvitation(creator, 3L)),
                () -> assertThrows(AuthorizationException.class, () -> foreignInvitationService.revokeInvitation(creator, 4L)));
    }

    @Test
    void invitationHistoryIsCountedAndFetchedInBoundedPages() {
        List<Invitation> invitations = List.of(registrationInvitation(activeUser(1L, "creator")));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult("select count(invitation)", 73L)
                .resultList("from Invitation invitation", invitations);
        InvitationService service = service(
                entityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        ApplicationUser creator = activeUser(1L, "creator");
        List<Invitation> invitationPage = service.listInvitations(creator, 50, 23);
        QueryPagination queryPagination = entityManagerStub.queryPaginations().getFirst();

        assertAll(
                () -> assertEquals(73L, service.countInvitations(creator)),
                () -> assertEquals(invitations, invitationPage),
                () -> assertEquals(50, queryPagination.firstResult()),
                () -> assertEquals(23, queryPagination.maximumResults()),
                () -> assertFalse(entityManagerStub.maximumResultLimitedQueryTexts().isEmpty()),
                () -> assertThrows(IllegalArgumentException.class, () -> service.listInvitations(creator, -1, 23)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.listInvitations(creator, 0, 0)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.listInvitations(
                                creator,
                                0,
                                InvitationService.MAXIMUM_INVITATIONS_PER_PAGE + 1)));
    }

    @Test
    void calendarAdminsCanListFullHistoryAndRevokeAvailableInvitationsCreatedByEditors() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser editor = activeUser(1L, "editor");
        ApplicationUser administrator = activeUser(2L, "administrator");
        Invitation editorInvitation = editorInvitation(editor, calendar);
        Invitation usedInvitation = editorInvitation(editor, calendar);
        usedInvitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        Invitation revokedInvitation = editorInvitation(editor, calendar);
        revokedInvitation.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        Invitation expiredInvitation = editorInvitation(editor, calendar);
        expiredInvitation.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        List<Invitation> calendarHistory = List.of(
                editorInvitation,
                usedInvitation,
                revokedInvitation,
                expiredInvitation);
        EntityManagerStub listEntityManagerStub = entityManagerStub()
                .resultList("where calendarMember.calendar = calendar", calendarHistory);
        InvitationService listService = service(
                listEntityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        List<Invitation> visibleInvitations = listService.listInvitations(
                administrator,
                0,
                InvitationService.MAXIMUM_INVITATIONS_PER_PAGE);

        RecordingAuditService auditService = new RecordingAuditService();
        InvitationService revokeService = service(
                entityManagerStub().singleResult("where invitation.id", editorInvitation),
                new FixedTokenService("unused"),
                auditService);
        revokeService.revokeInvitation(administrator, editorInvitation.getId());

        assertAll(
                () -> assertEquals(calendarHistory, visibleInvitations),
                () -> assertFalse(listEntityManagerStub.maximumResultLimitedQueryTexts().isEmpty()),
                () -> assertEquals(ZoneOffset.UTC, editorInvitation.getRevokedAt().getOffset()),
                () -> assertSame(calendar, auditService.calendar),
                () -> assertEquals("revoked", auditService.action));
    }

    private InvitationService serviceForInvitation(Invitation invitation) {
        EntityManagerStub entityManagerStub = entityManagerStub();
        if (invitation == null) {
            return service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        }
        entityManagerStub.singleResult("where invitation.invitationToken", invitation);
        if (invitation.getCreatedByUser() != null && invitation.getCreatedByUser().isActive()) {
            entityManagerStub.singleResult(
                    "where applicationUser.id = :invitationCreatorId",
                    invitation.getCreatedByUser());
        } else {
            entityManagerStub.singleResultNotFound("where applicationUser.id = :invitationCreatorId");
        }
        return service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
    }

    private InvitationService serviceForMissingInvitation() {
        return serviceForMissingInvitation(false, "");
    }

    private InvitationService serviceForMissingInvitation(
            boolean anyUserHasEverExisted,
            String bootstrapInvitationToken,
            RegistrationBootstrapState bootstrapState) {
        InvitationService service = service(
                entityManagerStub()
                        .singleResultNotFound("where invitation.invitationToken")
                        .singleResult("from RegistrationBootstrapState", bootstrapState)
                        .singleResult("select count(applicationUser)", anyUserHasEverExisted ? 1L : 0L),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        setField(service, "bootstrapInvitationToken", bootstrapInvitationToken);
        return service;
    }

    private InvitationService serviceForMissingInvitation(boolean anyUserHasEverExisted, String bootstrapInvitationToken) {
        return serviceForMissingInvitation(anyUserHasEverExisted, bootstrapInvitationToken, availableBootstrapState());
    }

    private InvitationService service(
            EntityManagerStub entityManagerStub,
            TokenService tokenService,
            AuditService auditService) {
        InvitationService service = new InvitationService();
        setField(service, "entityManager", entityManagerStub.entityManager());
        setField(service, "calendarAccessService", new AllowingAccessService());
        setField(service, "calendarService", new FixedCalendarService(activeCalendar(200L)));
        setField(service, "calendarMembershipService", new RecordingMembershipService());
        setField(service, "invitationPolicy", new InvitationPolicy());
        setField(service, "tokenService", tokenService);
        setField(service, "auditService", auditService);
        setField(service, "bootstrapInvitationToken", "");
        return service;
    }

    private Invitation registrationInvitation(ApplicationUser creator) {
        Invitation invitation = new Invitation();
        setEntityId(invitation, 300L);
        invitation.setInvitationToken("app-token-abcdefghijklmnopqrstuvwxyz");
        invitation.setCreatedByUser(creator);
        invitation.setCreatedAt(OffsetDateTime.parse("2026-07-08T12:00:00Z"));
        return invitation;
    }

    private Invitation editorInvitation(ApplicationUser creator, Calendar calendar) {
        Invitation invitation = registrationInvitation(creator);
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

    private ApplicationUser activeUser(Long id, String username) {
        ApplicationUser user = new ApplicationUser();
        setEntityId(user, id);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setActive(true);
        return user;
    }

    private RegistrationBootstrapState availableBootstrapState() {
        RegistrationBootstrapState bootstrapState = new RegistrationBootstrapState();
        setField(bootstrapState, "singletonId", RegistrationBootstrapState.SINGLETON_ID);
        return bootstrapState;
    }

    private static final class RecordingMembershipService extends CalendarMembershipService {
        private boolean membershipGranted;
        private Calendar grantedCalendar;
        private ApplicationUser grantedCreator;
        private ApplicationUser grantedUser;
        private CalendarRole grantedRole;

        @Override
        public Optional<app.membership.CalendarMember> grantMembershipFromAcceptedInvitation(
                Calendar calendar,
                ApplicationUser invitationCreator,
                ApplicationUser user,
                CalendarRole role) {
            membershipGranted = true;
            grantedCalendar = calendar;
            grantedCreator = invitationCreator;
            grantedUser = user;
            grantedRole = role;
            return Optional.of(new app.membership.CalendarMember());
        }
    }

    private static final class RejectingMembershipService extends CalendarMembershipService {
        @Override
        public Optional<app.membership.CalendarMember> grantMembershipFromAcceptedInvitation(
                Calendar calendar,
                ApplicationUser invitationCreator,
                ApplicationUser user,
                CalendarRole role) {
            return Optional.empty();
        }
    }

    private static final class RecordingAuditService extends AuditService {
        private Calendar calendar;
        private String entityType;
        private String action;

        @Override
        public void record(ApplicationUser actingUser, Calendar calendar, String entityType, Long entityId, String action, String details) {
            this.calendar = calendar;
            this.entityType = entityType;
            this.action = action;
        }
    }

    private static final class AllowingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
        }

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
        }
    }

    private static final class DenyingAccessService extends CalendarAccessService {
        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
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

}
