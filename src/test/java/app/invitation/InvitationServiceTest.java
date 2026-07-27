package app.invitation;

import static app.testsupport.ServiceTestSupport.entityManagerStub;
import static app.testsupport.ServiceTestSupport.queryParameter;
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
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InvitationServiceTest {
    private static final String VALID_BOOTSTRAP_INVITATION_TOKEN =
            "6RrWdXJO3P9mQ1fUh8zGkV2nY5cBsA7tEeL0iNxC4_o";
    private static final OffsetDateTime CURRENT_TIME =
            OffsetDateTime.parse("2026-07-24T12:00:00Z");

    @Test
    void activeUsersCanCreateRegistrationInvitations() {
        ApplicationUser actingUser = activeUser(1L, "piotr");
        EntityManagerStub entityManagerStub = registrationInvitationCreationEntityManagerStub(
                actingUser,
                0L,
                0L);
        RecordingAuditService auditService = new RecordingAuditService();
        InvitationService service = service(entityManagerStub, new FixedTokenService("app-token-abcdefghijklmnopqrstuvwxyz"), auditService);

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
        ApplicationUser actingUser = activeUser(1L, "editor");
        EntityManagerStub entityManagerStub = editorInvitationCreationEntityManagerStub(
                actingUser,
                calendar,
                0L,
                0L,
                0L);
        RecordingAuditService auditService = new RecordingAuditService();
        InvitationService service = service(entityManagerStub, new FixedTokenService("editor-token-abcdefghijklmnopqrstuvwxyz"), auditService);
        setField(service, "calendarAccessService", new AllowingAccessService());

        Invitation invitation = service.createCalendarEditorInvitation(actingUser, calendar.getId());

        assertAll(
                () -> assertSame(calendar, invitation.getCalendar()),
                () -> assertEquals(CalendarRole.EDITOR, invitation.getRole()),
                () -> assertEquals(invitation.getCreatedAt().plusDays(7), invitation.getExpiresAt()),
                () -> assertEquals(calendar, auditService.calendar),
                () -> assertEquals("app_invitation", auditService.entityType),
                () -> assertEquals("created", auditService.action),
                () -> assertEquals(
                        List.of(
                                new app.testsupport.ServiceTestSupport.FindLock(
                                        Calendar.class,
                                        calendar.getId(),
                                        jakarta.persistence.LockModeType.PESSIMISTIC_WRITE),
                                new app.testsupport.ServiceTestSupport.FindLock(
                                        ApplicationUser.class,
                                        actingUser.getId(),
                                        jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)),
                        entityManagerStub.findLocks(),
                        "Calendar invitation creation must lock the calendar before the user."));
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
                () -> service.createCalendarEditorInvitation(activeUser(1L, "unrelated-user"), 200L));
    }

    @Test
    void calendarInvitationCreationRechecksEditorAccessAfterTakingTheCalendarLock() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser actingUser = activeUser(1L, "editor");
        EntityManagerStub entityManagerStub = entityManagerStub()
                .find(ApplicationUser.class, actingUser.getId(), actingUser)
                .find(Calendar.class, calendar.getId(), calendar);
        InvitationService service = service(
                entityManagerStub,
                new FixedTokenService("editor-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());
        setField(service, "calendarAccessService", new EditAccessRevokedAfterInitialCheckAccessService());

        AuthorizationException exception = assertThrows(
                AuthorizationException.class,
                () -> service.createCalendarEditorInvitation(actingUser, calendar.getId()));

        assertAll(
                () -> assertEquals("Editor access is required.", exception.getMessage()),
                () -> assertTrue(entityManagerStub.findLocks().stream()
                        .anyMatch(findLock -> findLock.entityType() == Calendar.class
                                && findLock.id().equals(calendar.getId())
                                && findLock.lockMode() == jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)),
                () -> assertTrue(entityManagerStub.persistedObjects().isEmpty()));
    }

    @Test
    void registrationInvitationIssuanceStopsAtOutstandingAndRollingAccountBounds() {
        ApplicationUser actingUser = activeUser(1L, "creator");
        EntityManagerStub outstandingLimitEntityManagerStub =
                registrationInvitationCreationEntityManagerStub(
                        actingUser,
                        InvitationService.MAXIMUM_OUTSTANDING_REGISTRATION_INVITATIONS_PER_ACCOUNT - 1L,
                        InvitationService.MAXIMUM_OUTSTANDING_REGISTRATION_INVITATIONS_PER_ACCOUNT);
        InvitationService outstandingLimitService = service(
                outstandingLimitEntityManagerStub,
                new FixedTokenService("registration-limit-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());
        EntityManagerStub rollingLimitEntityManagerStub =
                registrationInvitationCreationEntityManagerStub(
                        actingUser,
                        InvitationService.MAXIMUM_INVITATIONS_PER_ACCOUNT_IN_ROLLING_WINDOW,
                        0L);
        InvitationService rollingLimitService = service(
                rollingLimitEntityManagerStub,
                new FixedTokenService("registration-limit-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());

        ValidationException outstandingLimitException = assertThrows(
                ValidationException.class,
                () -> outstandingLimitService.createRegistrationInvitation(actingUser));
        ValidationException rollingLimitException = assertThrows(
                ValidationException.class,
                () -> rollingLimitService.createRegistrationInvitation(actingUser));

        assertAll(
                () -> assertEquals(
                        "Too many active registration invitations. Revoke one before creating another.",
                        outstandingLimitException.getMessage()),
                () -> assertEquals(
                        "Invitation creation limit reached. Try again later.",
                        rollingLimitException.getMessage()),
                () -> assertTrue(outstandingLimitEntityManagerStub.persistedObjects().isEmpty()),
                () -> assertTrue(rollingLimitEntityManagerStub.persistedObjects().isEmpty()),
                () -> assertTrue(outstandingLimitEntityManagerStub.findLocks().stream()
                        .anyMatch(findLock -> findLock.entityType() == ApplicationUser.class
                                && findLock.lockMode()
                                        == jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)));
    }

    @Test
    void editorInvitationIssuanceStopsAtOutstandingAndRollingCalendarBounds() {
        ApplicationUser actingUser = activeUser(1L, "editor");
        Calendar calendar = activeCalendar(200L);
        EntityManagerStub outstandingLimitEntityManagerStub =
                editorInvitationCreationEntityManagerStub(
                        actingUser,
                        calendar,
                        0L,
                        InvitationService.MAXIMUM_OUTSTANDING_EDITOR_INVITATIONS_PER_CALENDAR,
                        0L);
        InvitationService outstandingLimitService = service(
                outstandingLimitEntityManagerStub,
                new FixedTokenService("editor-limit-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());
        EntityManagerStub rollingLimitEntityManagerStub =
                editorInvitationCreationEntityManagerStub(
                        actingUser,
                        calendar,
                        0L,
                        0L,
                        InvitationService.MAXIMUM_EDITOR_INVITATIONS_PER_CALENDAR_IN_ROLLING_WINDOW);
        InvitationService rollingLimitService = service(
                rollingLimitEntityManagerStub,
                new FixedTokenService("editor-limit-token-abcdefghijklmnopqrstuvwxyz"),
                new RecordingAuditService());

        ValidationException outstandingLimitException = assertThrows(
                ValidationException.class,
                () -> outstandingLimitService.createCalendarEditorInvitation(
                        actingUser,
                        calendar.getId()));
        ValidationException rollingLimitException = assertThrows(
                ValidationException.class,
                () -> rollingLimitService.createCalendarEditorInvitation(
                        actingUser,
                        calendar.getId()));
        String outstandingCapacityQuery = outstandingLimitEntityManagerStub.queryExecutions().stream()
                .map(queryExecution -> queryExecution.queryText())
                .filter(queryText -> queryText.contains("select count(invitation)"))
                .filter(queryText -> queryText.contains("invitation.calendar.id = :calendarId"))
                .filter(queryText -> queryText.contains("invitation.acceptedAt is null"))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(
                        "Too many outstanding editor invitations. Revoke one before creating another.",
                        outstandingLimitException.getMessage()),
                () -> assertEquals(
                        "Calendar invitation creation limit reached. Try again later.",
                        rollingLimitException.getMessage()),
                () -> assertTrue(outstandingLimitEntityManagerStub.persistedObjects().isEmpty()),
                () -> assertTrue(rollingLimitEntityManagerStub.persistedObjects().isEmpty()),
                () -> assertTrue(outstandingCapacityQuery.contains(
                        "invitation.revokedAt is null")),
                () -> assertTrue(outstandingCapacityQuery.contains(
                        "invitation.expiresAt > :currentTime")),
                () -> assertFalse(outstandingCapacityQuery.contains(
                        "invitation.createdByUser.active")),
                () -> assertFalse(outstandingCapacityQuery.contains(
                        "creatorMembership")),
                () -> assertTrue(rollingLimitEntityManagerStub.findLocks().stream()
                        .anyMatch(findLock -> findLock.entityType() == Calendar.class
                                && findLock.lockMode()
                                        == jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)));
    }

    @Test
    void registrationInvitationRegistersWithoutGrantingCalendarMembership() {
        Invitation invitation = registrationInvitation(activeUser(1L, "creator"));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult(
                        "where invitation.invitationToken",
                        invitation,
                        queryParameter("invitationToken", invitation.getInvitationToken()))
                .singleResult(
                        "where applicationUser.id = :invitationCreatorId",
                        invitation.getCreatedByUser(),
                        queryParameter("invitationCreatorId", invitation.getCreatedByUser().getId()));
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
                        .anyMatch(queryText -> queryText.contains("where invitation.invitationToken"))),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains(
                                "where applicationUser.id = :invitationCreatorId"))));
    }

    @Test
    void usableInvitationPreviewIsNonConsumingAndDoesNotLockTheInvitation() {
        Invitation invitation = editorInvitation(
                activeUser(1L, "creator"),
                activeCalendar(200L));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult(
                        "where invitation.invitationToken",
                        invitation,
                        queryParameter("invitationToken", invitation.getInvitationToken()))
                .singleResult(
                        "where applicationUser.id = :invitationCreatorId",
                        invitation.getCreatedByUser(),
                        queryParameter("invitationCreatorId", invitation.getCreatedByUser().getId()));
        InvitationService service = service(
                entityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        InvitationAdmissionPreview preview = service.previewAdmission(
                " " + invitation.getInvitationToken() + " ");

        assertAll(
                () -> assertTrue(preview.isAvailable()),
                () -> assertTrue(preview.isCalendarEditorInvitation()),
                () -> assertFalse(preview.isRegistrationInvitation()),
                () -> assertEquals(invitation.getCalendar().getName(), preview.getCalendarName()),
                () -> assertEquals(invitation.getExpiresAt(), preview.getExpiresAt()),
                () -> assertNull(invitation.getAcceptedAt()),
                () -> assertNull(invitation.getAcceptedByUser()),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .noneMatch(queryText -> queryText.contains("where invitation.invitationToken"))),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .noneMatch(queryText -> queryText.contains(
                                "where applicationUser.id = :invitationCreatorId"))));
    }

    @Test
    void registrationInvitationPreviewDoesNotLockTheCreator() {
        Invitation invitation = registrationInvitation(activeUser(1L, "creator"));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult(
                        "where invitation.invitationToken",
                        invitation,
                        queryParameter("invitationToken", invitation.getInvitationToken()))
                .singleResult(
                        "where applicationUser.id = :invitationCreatorId",
                        invitation.getCreatedByUser(),
                        queryParameter("invitationCreatorId", invitation.getCreatedByUser().getId()));

        InvitationAdmissionPreview preview = service(
                        entityManagerStub,
                        new FixedTokenService("unused"),
                        new RecordingAuditService())
                .previewAdmission(invitation.getInvitationToken());

        assertAll(
                () -> assertTrue(preview.isAvailable()),
                () -> assertTrue(preview.isRegistrationInvitation()),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().isEmpty()));
    }

    @Test
    void unavailableInvitationPreviewIsGenericAndSignedInUsersCannotConsumeRegistrationLinks() {
        Invitation inactiveCreatorInvitation = registrationInvitation(
                activeUser(1L, "inactive-creator"));
        inactiveCreatorInvitation.getCreatedByUser().setActive(false);
        InvitationAdmissionPreview unavailablePreview = serviceForInvitation(
                        inactiveCreatorInvitation)
                .previewAdmission(inactiveCreatorInvitation.getInvitationToken());
        Invitation acceptedInvitation = editorInvitation(
                activeUser(4L, "accepted-creator"),
                activeCalendar(400L));
        acceptedInvitation.setAcceptedAt(CURRENT_TIME.minusMinutes(1));
        InvitationAdmissionPreview acceptedInvitationPreview = serviceForInvitation(
                        acceptedInvitation)
                .previewAdmission(acceptedInvitation.getInvitationToken());

        Invitation registrationInvitation = registrationInvitation(activeUser(2L, "creator"));
        InvitationService registrationInvitationService = serviceForInvitation(registrationInvitation);
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> registrationInvitationService.acceptInvitation(
                        registrationInvitation.getInvitationToken(),
                        activeUser(3L, "signed-in-user")));

        assertAll(
                () -> assertFalse(unavailablePreview.isAvailable()),
                () -> assertFalse(unavailablePreview.isRegistrationInvitation()),
                () -> assertFalse(unavailablePreview.isCalendarEditorInvitation()),
                () -> assertFalse(acceptedInvitationPreview.isAvailable()),
                () -> assertFalse(acceptedInvitationPreview.isRegistrationInvitation()),
                () -> assertFalse(acceptedInvitationPreview.isCalendarEditorInvitation()),
                () -> assertEquals(
                        "Invitation is invalid or no longer available.",
                        exception.getMessage()),
                () -> assertNull(registrationInvitation.getAcceptedAt()),
                () -> assertNull(registrationInvitation.getAcceptedByUser()));
    }

    @Test
    void registrationInvitationIsGenericInvalidWhenItsCreatorIsInactive() {
        ApplicationUser inactiveCreator = activeUser(1L, "inactive-creator");
        inactiveCreator.setActive(false);
        Invitation invitation = registrationInvitation(inactiveCreator);
        InvitationService service = serviceForInvitation(invitation);

        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> service.claimRegistrationAdmission(invitation.getInvitationToken()));

        assertEquals("Invitation is invalid or no longer available.", exception.getMessage());
    }

    @Test
    void editorInvitationRegistersAndGrantsEditorMembership() {
        Calendar calendar = activeCalendar(200L);
        Invitation invitation = editorInvitation(activeUser(1L, "creator"), calendar);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .singleResult(
                        "where invitation.invitationToken",
                        invitation,
                        queryParameter("invitationToken", invitation.getInvitationToken()))
                .singleResult(
                        "where applicationUser.id = :invitationCreatorId",
                        invitation.getCreatedByUser(),
                        queryParameter("invitationCreatorId", invitation.getCreatedByUser().getId()));
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
                () -> assertEquals(ZoneOffset.UTC, invitation.getAcceptedAt().getOffset()),
                () -> assertTrue(entityManagerStub.lockedQueryTexts().stream()
                        .noneMatch(queryText -> queryText.contains(
                                "where applicationUser.id = :invitationCreatorId"))));
    }

    @Test
    void blankAcceptedRevokedExpiredMissingAndMalformedInvitationsCannotRegister() {
        Invitation acceptedInvitation = registrationInvitation(activeUser(1L, "creator"));
        acceptedInvitation.setAcceptedAt(CURRENT_TIME);
        Invitation revokedInvitation = registrationInvitation(activeUser(1L, "creator"));
        revokedInvitation.setRevokedAt(CURRENT_TIME);
        Invitation expiredInvitation = registrationInvitation(activeUser(1L, "creator"));
        expiredInvitation.setExpiresAt(OffsetDateTime.parse("2026-07-08T11:00:00Z"));
        Invitation malformedInvitation = registrationInvitation(activeUser(1L, "creator"));
        malformedInvitation.setCalendar(activeCalendar(200L));

        assertAll(
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(null).requireAdmission("   ")),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(acceptedInvitation).requireAdmission(acceptedInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(revokedInvitation).requireAdmission(revokedInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(expiredInvitation).requireAdmission(expiredInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForInvitation(malformedInvitation).requireAdmission(malformedInvitation.getInvitationToken())),
                () -> assertThrows(ValidationException.class, () -> serviceForMissingInvitation().requireAdmission("missing-token")));
    }

    @Test
    void oversizedAndRedirectUnsafeTokensAreRejectedBeforeAnyDatabaseQuery() {
        InvitationService service = service(
                entityManagerStub(),
                new FixedTokenService("unused"),
                new RecordingAuditService());

        assertAll(
                () -> assertThrows(
                        ValidationException.class,
                        () -> service.requireAdmission(
                                "a".repeat(InvitationToken.MAXIMUM_LENGTH + 1))),
                () -> assertThrows(
                        ValidationException.class,
                        () -> service.requireAdmission("token\\suffix")),
                () -> assertThrows(
                        ValidationException.class,
                        () -> service.claimRegistrationAdmission("token\r\nsuffix")));
    }

    @Test
    void bootstrapAdmissionIsClaimedOnceAndRejectsDatabasesWhereAnyUserHasEverExisted() {
        RegistrationBootstrapState availableBootstrapState = availableBootstrapState();
        InvitationService serviceWithoutUsers = serviceForMissingInvitation(
                false,
                VALID_BOOTSTRAP_INVITATION_TOKEN,
                availableBootstrapState);
        InvitationService serviceWithUsers = serviceForMissingInvitation(
                true,
                VALID_BOOTSTRAP_INVITATION_TOKEN,
                availableBootstrapState());

        RegistrationAdmission admission = serviceWithoutUsers.claimRegistrationAdmission(
                " " + VALID_BOOTSTRAP_INVITATION_TOKEN + " ");

        assertAll(
                () -> assertTrue(admission.bootstrap()),
                () -> assertNull(admission.invitation()),
                () -> assertEquals(ZoneOffset.UTC, availableBootstrapState.getConsumedAt().getOffset()),
                () -> assertThrows(
                        ValidationException.class,
                        () -> serviceWithoutUsers.claimRegistrationAdmission(
                                VALID_BOOTSTRAP_INVITATION_TOKEN)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> serviceWithUsers.requireAdmission(
                                VALID_BOOTSTRAP_INVITATION_TOKEN)),
                () -> assertThrows(
                        ValidationException.class,
                        () -> serviceForMissingInvitation().requireAdmission("missing-token")));
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
    void creatorsCanRevokeAvailableInvitationsButNotAcceptedExpiredOrForeignInvitations() {
        ApplicationUser creator = activeUser(1L, "creator");
        Invitation unacceptedInvitation = registrationInvitation(creator);
        EntityManagerStub unacceptedInvitationEntityManagerStub = entityManagerStub()
                .singleResult(
                        "where invitation.id",
                        unacceptedInvitation,
                        queryParameter("invitationId", 1L));
        InvitationService unacceptedInvitationService = service(unacceptedInvitationEntityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());

        unacceptedInvitationService.revokeInvitation(creator, 1L);

        Invitation acceptedInvitation = registrationInvitation(creator);
        acceptedInvitation.setAcceptedAt(CURRENT_TIME);
        InvitationService acceptedInvitationService = service(
                entityManagerStub().singleResult(
                        "where invitation.id",
                        acceptedInvitation,
                        queryParameter("invitationId", 2L)),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        Invitation expiredInvitation = registrationInvitation(creator);
        expiredInvitation.setExpiresAt(CURRENT_TIME.minusSeconds(1));
        InvitationService expiredInvitationService = service(
                entityManagerStub().singleResult(
                        "where invitation.id",
                        expiredInvitation,
                        queryParameter("invitationId", 3L)),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        Invitation foreignInvitation = registrationInvitation(activeUser(2L, "other"));
        InvitationService foreignInvitationService = service(
                entityManagerStub().singleResult(
                        "where invitation.id",
                        foreignInvitation,
                        queryParameter("invitationId", 4L)),
                new FixedTokenService("unused"),
                new RecordingAuditService());

        assertAll(
                () -> assertEquals(ZoneOffset.UTC, unacceptedInvitation.getRevokedAt().getOffset()),
                () -> assertTrue(unacceptedInvitationEntityManagerStub.lockedQueryTexts().stream()
                        .anyMatch(queryText -> queryText.contains("where invitation.id"))),
                () -> assertTrue(unacceptedInvitationEntityManagerStub.lockedQueryTexts().stream()
                        .filter(queryText -> queryText.contains("where invitation.id"))
                        .noneMatch(queryText -> queryText.contains("join fetch"))),
                () -> assertThrows(ValidationException.class, () -> acceptedInvitationService.revokeInvitation(creator, 2L)),
                () -> assertThrows(ValidationException.class, () -> expiredInvitationService.revokeInvitation(creator, 3L)),
                () -> assertThrows(AuthorizationException.class, () -> foreignInvitationService.revokeInvitation(creator, 4L)));
    }

    @Test
    void invitationHistoryIsCountedAndFetchedInBoundedPages() {
        Invitation invitation = registrationInvitation(activeUser(1L, "creator"));
        InvitationSummary invitationSummary = invitationSummary(invitation);
        List<InvitationSummary> invitations = List.of(invitationSummary);
        ApplicationUser creator = activeUser(1L, "creator");
        EntityManagerStub entityManagerStub = entityManagerStub()
                .resultList(
                        "select calendarMembership.calendar.id",
                        List.of(),
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("adminRole", CalendarRole.ADMIN))
                .singleResult(
                        "select count(invitation)",
                        73L,
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("snapshotMaximumInvitationId", 300L))
                .resultList(
                        "select invitation from Invitation invitation",
                        List.of(invitation),
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("snapshotMaximumInvitationId", 300L))
                .resultList(
                        "select invitation.id from Invitation invitation",
                        List.of(invitation.getId()),
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("pageInvitationIds", List.of(invitation.getId())),
                        queryParameter("currentTime", CURRENT_TIME),
                        queryParameter("editorRole", CalendarRole.EDITOR));
        InvitationService service = service(
                entityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        OffsetDateTime currentTime = CURRENT_TIME;
        List<InvitationSummary> invitationPage =
                service.listInvitations(creator, 300L, 50, 23, currentTime);
        QueryPagination queryPagination = entityManagerStub.queryPaginations().stream()
                .filter(pagination -> pagination.queryText().contains(
                        "select invitation from Invitation invitation"))
                .findFirst()
                .orElseThrow();
        String availabilityQuery = entityManagerStub.queryPaginations().stream()
                .map(QueryPagination::queryText)
                .filter(queryText -> queryText.contains(
                        "select invitation.id from Invitation invitation"))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(73L, service.countInvitations(creator, 300L)),
                () -> assertEquals(invitations, invitationPage),
                () -> assertEquals(50, queryPagination.firstResult()),
                () -> assertEquals(23, queryPagination.maximumResults()),
                () -> assertFalse(queryPagination.queryText().contains("case when")),
                () -> assertTrue(availabilityQuery.contains(
                        "invitation.createdByUser.active = true")),
                () -> assertTrue(availabilityQuery.contains(
                        "where invitation.id in :pageInvitationIds")),
                () -> assertTrue(availabilityQuery.contains(
                        "(invitation.calendar is null and invitation.role is null)")),
                () -> assertFalse(availabilityQuery.contains(
                        "invitation.calendar.active")),
                () -> assertTrue(availabilityQuery.contains(
                        "creatorMembership.calendar.active = true")),
                () -> assertTrue(queryPagination.queryText().contains(
                        "order by invitation.createdAt desc, invitation.id desc")),
                () -> assertFalse(entityManagerStub.maximumResultLimitedQueryTexts().isEmpty()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.listInvitations(creator, 300L, -1, 23, currentTime)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.listInvitations(creator, 300L, 0, 0, currentTime)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.listInvitations(
                                creator,
                                300L,
                                0,
                                InvitationService.MAXIMUM_INVITATIONS_PER_PAGE + 1,
                                currentTime)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.listInvitations(creator, 300L, 0, 23, null)));
    }

    @Test
    void invitationHistoryUsesAnImmutableIdentifierSnapshotAndProjectsAdmissionValidity() {
        ApplicationUser creator = activeUser(1L, "creator");
        Invitation invitation = editorInvitation(creator, activeCalendar(200L));
        EntityManagerStub entityManagerStub = entityManagerStub()
                .resultList(
                        "select calendarMembership.calendar.id",
                        List.of(),
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("adminRole", CalendarRole.ADMIN))
                .singleResult(
                        "select coalesce(max(invitation.id), 0)",
                        450L,
                        queryParameter("actingUserId", creator.getId()))
                .resultList(
                        "select invitation from Invitation invitation",
                        List.of(invitation),
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("snapshotMaximumInvitationId", 450L))
                .resultList(
                        "select invitation.id from Invitation invitation",
                        List.of(),
                        queryParameter("actingUserId", creator.getId()),
                        queryParameter("pageInvitationIds", List.of(invitation.getId())),
                        queryParameter("currentTime", CURRENT_TIME),
                        queryParameter("editorRole", CalendarRole.EDITOR));
        InvitationService service = service(
                entityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        long snapshotMaximumInvitationId = service.captureInvitationSnapshot(creator);
        List<InvitationSummary> invitationPage = service.listInvitations(
                creator,
                snapshotMaximumInvitationId,
                0,
                InvitationService.MAXIMUM_INVITATIONS_PER_PAGE,
                CURRENT_TIME);
        QueryPagination invitationQueryPagination = entityManagerStub.queryPaginations().stream()
                .filter(pagination -> pagination.queryText().contains(
                        "select invitation from Invitation invitation"))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(450L, snapshotMaximumInvitationId),
                () -> assertEquals(1, invitationPage.size()),
                () -> assertFalse(invitationPage.getFirst().admissionAvailable()),
                () -> assertTrue(invitationQueryPagination.queryText()
                        .contains("invitation.id <= :snapshotMaximumInvitationId")),
                () -> assertTrue(invitationQueryPagination.queryText()
                        .contains("order by invitation.createdAt desc, invitation.id desc")),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> service.countInvitations(creator, -1L)));
    }

    @Test
    void calendarAdminsCanListFullHistoryAndRevokeAvailableInvitationsCreatedByEditors() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser editor = activeUser(1L, "editor");
        ApplicationUser administrator = activeUser(2L, "administrator");
        Invitation editorInvitation = editorInvitation(editor, calendar);
        Invitation acceptedInvitation = editorInvitation(editor, calendar);
        setEntityId(acceptedInvitation, 301L);
        acceptedInvitation.setAcceptedAt(CURRENT_TIME.minusDays(1));
        Invitation revokedInvitation = editorInvitation(editor, calendar);
        setEntityId(revokedInvitation, 302L);
        revokedInvitation.setRevokedAt(CURRENT_TIME.minusDays(1));
        Invitation expiredInvitation = editorInvitation(editor, calendar);
        setEntityId(expiredInvitation, 303L);
        expiredInvitation.setExpiresAt(CURRENT_TIME.minusDays(1));
        List<InvitationSummary> calendarHistory = List.of(
                invitationSummary(editorInvitation),
                invitationSummary(acceptedInvitation, false),
                invitationSummary(revokedInvitation, false),
                invitationSummary(expiredInvitation, false));
        EntityManagerStub listEntityManagerStub = entityManagerStub()
                .resultList(
                        "select calendarMembership.calendar.id",
                        List.of(calendar.getId()),
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("adminRole", CalendarRole.ADMIN))
                .resultList(
                        "select invitation from Invitation invitation",
                        List.of(
                                editorInvitation,
                                acceptedInvitation,
                                revokedInvitation,
                                expiredInvitation),
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("administeredCalendarIds", List.of(calendar.getId())),
                        queryParameter("adminRole", CalendarRole.ADMIN),
                        queryParameter("snapshotMaximumInvitationId", 303L))
                .resultList(
                        "select invitation.id from Invitation invitation",
                        List.of(editorInvitation.getId()),
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("administeredCalendarIds", List.of(calendar.getId())),
                        queryParameter("adminRole", CalendarRole.ADMIN),
                        queryParameter(
                                "pageInvitationIds",
                                List.of(
                                        editorInvitation.getId(),
                                        acceptedInvitation.getId(),
                                        revokedInvitation.getId(),
                                        expiredInvitation.getId())),
                        queryParameter("currentTime", CURRENT_TIME),
                        queryParameter("editorRole", CalendarRole.EDITOR));
        InvitationService listService = service(
                listEntityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        List<InvitationSummary> visibleInvitations = listService.listInvitations(
                administrator,
                303L,
                0,
                InvitationService.MAXIMUM_INVITATIONS_PER_PAGE,
                CURRENT_TIME);

        RecordingAuditService auditService = new RecordingAuditService();
        FixedCalendarService calendarService = new FixedCalendarService(calendar);
        InvitationService revokeService = service(
                entityManagerStub().singleResult(
                        "where invitation.id",
                        editorInvitation,
                        queryParameter("invitationId", editorInvitation.getId())),
                new FixedTokenService("unused"),
                auditService);
        setField(revokeService, "calendarService", calendarService);
        setField(revokeService, "calendarAccessService", new AdminAccessAfterCalendarLockService(calendarService));
        revokeService.revokeInvitation(administrator, editorInvitation.getId());

        assertAll(
                () -> assertEquals(calendarHistory, visibleInvitations),
                () -> assertFalse(listEntityManagerStub.maximumResultLimitedQueryTexts().isEmpty()),
                () -> assertEquals(ZoneOffset.UTC, editorInvitation.getRevokedAt().getOffset()),
                () -> assertEquals(1, calendarService.childMutationLoadCount),
                () -> assertSame(calendar, auditService.calendar),
                () -> assertEquals("revoked", auditService.action));
    }

    @Test
    void invitationHistoryUsesMappedCompositeMembershipPathsAndRechecksAdminAuthorization() {
        Calendar calendar = activeCalendar(200L);
        ApplicationUser administrator = activeUser(2L, "administrator");
        Invitation invitation = editorInvitation(activeUser(1L, "editor"), calendar);
        EntityManagerStub entityManagerStub = entityManagerStub()
                .resultList(
                        "select calendarMembership.calendar.id",
                        List.of(calendar.getId()),
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("adminRole", CalendarRole.ADMIN))
                .singleResult(
                        "select coalesce(max(invitation.id), 0)",
                        300L,
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("administeredCalendarIds", List.of(calendar.getId())),
                        queryParameter("adminRole", CalendarRole.ADMIN))
                .resultList(
                        "select invitation from Invitation invitation",
                        List.of(invitation),
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("administeredCalendarIds", List.of(calendar.getId())),
                        queryParameter("adminRole", CalendarRole.ADMIN),
                        queryParameter("snapshotMaximumInvitationId", 300L))
                .resultList(
                        "select invitation.id from Invitation invitation",
                        List.of(),
                        queryParameter("actingUserId", administrator.getId()),
                        queryParameter("administeredCalendarIds", List.of(calendar.getId())),
                        queryParameter("adminRole", CalendarRole.ADMIN),
                        queryParameter("pageInvitationIds", List.of(invitation.getId())),
                        queryParameter("currentTime", CURRENT_TIME),
                        queryParameter("editorRole", CalendarRole.EDITOR));
        InvitationService service = service(
                entityManagerStub,
                new FixedTokenService("unused"),
                new RecordingAuditService());

        long snapshotMaximumInvitationId = service.captureInvitationSnapshot(administrator);
        List<InvitationSummary> visibleInvitations = service.listInvitations(
                administrator,
                snapshotMaximumInvitationId,
                0,
                InvitationService.MAXIMUM_INVITATIONS_PER_PAGE,
                CURRENT_TIME);
        List<String> invitationQueries = entityManagerStub.queryExecutions().stream()
                .map(queryExecution -> queryExecution.queryText())
                .filter(queryText -> queryText.contains("from Invitation invitation"))
                .toList();

        assertAll(
                () -> assertEquals(300L, snapshotMaximumInvitationId),
                () -> assertEquals(1, visibleInvitations.size()),
                () -> assertFalse(visibleInvitations.getFirst().admissionAvailable()),
                () -> assertEquals(3, invitationQueries.size()),
                () -> assertTrue(invitationQueries.stream().allMatch(queryText -> queryText.contains(
                        "invitation.calendar.id in :administeredCalendarIds"))),
                () -> assertTrue(invitationQueries.stream().allMatch(queryText -> queryText.contains(
                        "select currentAdministratorMembership.calendar.id"))),
                () -> assertTrue(invitationQueries.stream().noneMatch(queryText -> queryText.contains(
                        "select currentAdministratorMembership.id"))),
                () -> assertTrue(invitationQueries.stream().allMatch(queryText -> queryText.contains(
                        "currentAdministratorMembership.calendar.id = invitation.calendar.id"))),
                () -> assertTrue(invitationQueries.stream().allMatch(queryText -> queryText.contains(
                        "currentAdministratorMembership.user.id = :actingUserId"))),
                () -> assertTrue(invitationQueries.stream().allMatch(queryText -> queryText.contains(
                        "currentAdministratorMembership.role = :adminRole"))),
                () -> assertTrue(invitationQueries.stream().allMatch(queryText -> queryText.contains(
                        "currentAdministratorMembership.active = true"))),
                () -> assertTrue(invitationQueries.stream().anyMatch(queryText -> queryText.contains(
                        "select creatorMembership.calendar.id"))),
                () -> assertTrue(invitationQueries.stream().anyMatch(queryText -> queryText.contains(
                        "creatorMembership.calendar.id = invitation.calendar.id"))),
                () -> assertTrue(invitationQueries.stream().anyMatch(queryText -> queryText.contains(
                        "creatorMembership.user.id = invitation.createdByUser.id"))),
                () -> assertTrue(invitationQueries.stream().noneMatch(queryText -> queryText.contains(
                        "select creatorMembership.id"))));
    }

    private InvitationService serviceForInvitation(Invitation invitation) {
        EntityManagerStub entityManagerStub = entityManagerStub();
        if (invitation == null) {
            return service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
        }
        entityManagerStub.singleResult(
                "where invitation.invitationToken",
                invitation,
                queryParameter("invitationToken", invitation.getInvitationToken()));
        if (invitation.getCreatedByUser() != null && invitation.getCreatedByUser().isActive()) {
            entityManagerStub.singleResult(
                    "where applicationUser.id = :invitationCreatorId",
                    invitation.getCreatedByUser(),
                    queryParameter("invitationCreatorId", invitation.getCreatedByUser().getId()));
        } else {
            entityManagerStub.singleResultNotFound(
                    "where applicationUser.id = :invitationCreatorId",
                    queryParameter("invitationCreatorId", invitation.getCreatedByUser().getId()));
        }
        return service(entityManagerStub, new FixedTokenService("unused"), new RecordingAuditService());
    }

    private InvitationService serviceForMissingInvitation() {
        return service(
                entityManagerStub().singleResultNotFound(
                        "where invitation.invitationToken",
                        queryParameter("invitationToken", "missing-token")),
                new FixedTokenService("unused"),
                new RecordingAuditService());
    }

    private InvitationService serviceForMissingInvitation(
            boolean anyUserHasEverExisted,
            String bootstrapInvitationToken,
            RegistrationBootstrapState bootstrapState) {
        InvitationService service = service(
                entityManagerStub()
                        .singleResultNotFound(
                                "where invitation.invitationToken",
                                queryParameter("invitationToken", bootstrapInvitationToken))
                        .singleResult(
                                "from RegistrationBootstrapState",
                                bootstrapState,
                                queryParameter(
                                        "singletonId",
                                        RegistrationBootstrapState.SINGLETON_ID))
                        .singleResult("select count(applicationUser)", anyUserHasEverExisted ? 1L : 0L),
                new FixedTokenService("unused"),
                new RecordingAuditService());
        RegistrationInvitationConfiguration configuration =
                new RegistrationInvitationConfiguration(bootstrapInvitationToken);
        configuration.validate();
        setField(service, "registrationInvitationConfiguration", configuration);
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
        setField(service, "clock", Clock.fixed(CURRENT_TIME.toInstant(), ZoneOffset.UTC));
        setField(service, "calendarAccessService", new AllowingAccessService());
        setField(service, "calendarService", new FixedCalendarService(activeCalendar(200L)));
        setField(service, "calendarMembershipService", new RecordingMembershipService());
        setField(service, "invitationPolicy", new InvitationPolicy());
        setField(service, "tokenService", tokenService);
        setField(service, "auditService", auditService);
        setField(
                service,
                "registrationInvitationConfiguration",
                new RegistrationInvitationConfiguration(""));
        return service;
    }

    private EntityManagerStub registrationInvitationCreationEntityManagerStub(
            ApplicationUser actingUser,
            long recentInvitationCount,
            long outstandingInvitationCount) {
        return entityManagerStub()
                .find(ApplicationUser.class, actingUser.getId(), actingUser)
                .singleResult(
                        "where invitation.createdByUser.id = :actingUserId and invitation.createdAt",
                        recentInvitationCount,
                        queryParameter("actingUserId", actingUser.getId()),
                        queryParameter(
                                "rollingWindowStart",
                                CURRENT_TIME.minus(InvitationService.INVITATION_ISSUANCE_ROLLING_WINDOW)))
                .singleResult(
                        "and invitation.calendar is null",
                        outstandingInvitationCount,
                        queryParameter("actingUserId", actingUser.getId()),
                        queryParameter("currentTime", CURRENT_TIME));
    }

    private EntityManagerStub editorInvitationCreationEntityManagerStub(
            ApplicationUser actingUser,
            Calendar calendar,
            long recentAccountInvitationCount,
            long outstandingCalendarInvitationCount,
            long recentCalendarInvitationCount) {
        return entityManagerStub()
                .find(ApplicationUser.class, actingUser.getId(), actingUser)
                .find(Calendar.class, calendar.getId(), calendar)
                .singleResult(
                        "where invitation.createdByUser.id = :actingUserId and invitation.createdAt",
                        recentAccountInvitationCount,
                        queryParameter("actingUserId", actingUser.getId()),
                        queryParameter(
                                "rollingWindowStart",
                                CURRENT_TIME.minus(InvitationService.INVITATION_ISSUANCE_ROLLING_WINDOW)))
                .singleResult(
                        "and invitation.acceptedAt is null",
                        outstandingCalendarInvitationCount,
                        queryParameter("calendarId", calendar.getId()),
                        queryParameter("editorRole", CalendarRole.EDITOR),
                        queryParameter("currentTime", CURRENT_TIME))
                .singleResult(
                        "where invitation.calendar.id = :calendarId and invitation.role = :editorRole and invitation.createdAt",
                        recentCalendarInvitationCount,
                        queryParameter("calendarId", calendar.getId()),
                        queryParameter("editorRole", CalendarRole.EDITOR),
                        queryParameter(
                                "rollingWindowStart",
                                CURRENT_TIME.minus(InvitationService.INVITATION_ISSUANCE_ROLLING_WINDOW)));
    }

    private Invitation registrationInvitation(ApplicationUser creator) {
        Invitation invitation = new Invitation();
        setEntityId(invitation, 300L);
        invitation.setInvitationToken("app-token-abcdefghijklmnopqrstuvwxyz");
        invitation.setCreatedByUser(creator);
        OffsetDateTime createdAt = CURRENT_TIME.minusHours(1);
        invitation.setCreatedAt(createdAt);
        invitation.setExpiresAt(createdAt.plusDays(7));
        return invitation;
    }

    private Invitation editorInvitation(ApplicationUser creator, Calendar calendar) {
        Invitation invitation = registrationInvitation(creator);
        invitation.setCalendar(calendar);
        invitation.setRole(CalendarRole.EDITOR);
        return invitation;
    }

    private InvitationSummary invitationSummary(Invitation invitation) {
        return invitationSummary(invitation, true);
    }

    private InvitationSummary invitationSummary(
            Invitation invitation,
            boolean admissionAvailable) {
        return new InvitationSummary(
                invitation.getId(),
                invitation.getInvitationToken(),
                invitation.getCalendar() == null
                        ? null
                        : invitation.getCalendar().getName(),
                invitation.getRevokedAt(),
                invitation.getAcceptedAt(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt(),
                admissionAvailable);
    }

    private Calendar activeCalendar(Long id) {
        Calendar calendar = new Calendar();
        setEntityId(calendar, id);
        calendar.setName("Kayaking");
        calendar.setCalendarLinkToken("Abc_123-xY0");
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
        public Optional<app.membership.CalendarMembership> grantMembershipFromAcceptedInvitation(
                Calendar calendar,
                ApplicationUser invitationCreator,
                ApplicationUser user,
                CalendarRole role) {
            membershipGranted = true;
            grantedCalendar = calendar;
            grantedCreator = invitationCreator;
            grantedUser = user;
            grantedRole = role;
            return Optional.of(new app.membership.CalendarMembership());
        }
    }

    private static final class RejectingMembershipService extends CalendarMembershipService {
        @Override
        public Optional<app.membership.CalendarMembership> grantMembershipFromAcceptedInvitation(
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

    private static final class EditAccessRevokedAfterInitialCheckAccessService extends CalendarAccessService {
        private int editChecks;

        @Override
        public void requireCanEdit(ApplicationUser user, Long calendarId) {
            editChecks++;
            if (editChecks > 1) {
                throw new AuthorizationException("Editor access is required.");
            }
        }
    }

    private static final class AdminAccessAfterCalendarLockService extends CalendarAccessService {
        private final FixedCalendarService calendarService;

        private AdminAccessAfterCalendarLockService(FixedCalendarService calendarService) {
            this.calendarService = calendarService;
        }

        @Override
        public void requireCanAdminister(ApplicationUser user, Long calendarId) {
            if (calendarService.childMutationLoadCount == 0) {
                throw new AssertionError("Admin access was checked before the calendar lock was taken.");
            }
        }
    }

    private static final class FixedCalendarService extends CalendarService {
        private final Calendar calendar;
        private int childMutationLoadCount;

        private FixedCalendarService(Calendar calendar) {
            this.calendar = calendar;
        }

        @Override
        public Calendar requireActiveCalendarForChildMutation(Long calendarId) {
            childMutationLoadCount++;
            return calendar;
        }
    }

    private static final class FixedTokenService extends TokenService {
        private final String token;

        private FixedTokenService(String token) {
            this.token = token;
        }

        @Override
        public String generateInvitationToken() {
            return token;
        }
    }

}
