package app.invitation;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.user.ApplicationUser;
import app.user.RegistrationAdmission;
import app.user.RegistrationBootstrapState;
import app.util.AuthorizationException;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Stateless
public class InvitationService {
    static final int MAXIMUM_INVITATIONS_PER_PAGE = 50;
    public static final int MAXIMUM_OUTSTANDING_REGISTRATION_INVITATIONS_PER_ACCOUNT = 20;
    public static final int MAXIMUM_INVITATIONS_PER_ACCOUNT_IN_ROLLING_WINDOW = 100;
    public static final int MAXIMUM_OUTSTANDING_EDITOR_INVITATIONS_PER_CALENDAR = 50;
    public static final int MAXIMUM_EDITOR_INVITATIONS_PER_CALENDAR_IN_ROLLING_WINDOW = 100;
    static final Duration INVITATION_ISSUANCE_ROLLING_WINDOW = Duration.ofHours(24);

    private static final String CREATOR_INVITATION_PREDICATE =
            "invitation.createdByUser.id = :actingUserId";

    private static final String CURRENT_ADMIN_INVITATION_PREDICATE =
            "invitation.calendar.id in :administeredCalendarIds "
                    + "and exists ("
                    + "select currentAdministratorMembership.calendar.id "
                    + "from CalendarMembership currentAdministratorMembership "
                    + "where currentAdministratorMembership.calendar.id = invitation.calendar.id "
                    + "and currentAdministratorMembership.user.id = :actingUserId "
                    + "and currentAdministratorMembership.role = :adminRole "
                    + "and currentAdministratorMembership.active = true "
                    + "and currentAdministratorMembership.user.active = true "
                    + "and currentAdministratorMembership.calendar.active = true)";

    private static final String USABLE_INVITATION_PREDICATE =
            "invitation.acceptedAt is null "
                    + "and invitation.revokedAt is null "
                    + "and invitation.expiresAt > :currentTime "
                    + "and invitation.createdByUser.active = true "
                    + "and ((invitation.calendar is null and invitation.role is null) "
                    + "or (invitation.role = :editorRole "
                    + "and exists ("
                    + "select creatorMembership.calendar.id from CalendarMembership creatorMembership "
                    + "where creatorMembership.calendar.id = invitation.calendar.id "
                    + "and creatorMembership.user.id = invitation.createdByUser.id "
                    + "and creatorMembership.active = true "
                    + "and creatorMembership.user.active = true "
                    + "and creatorMembership.calendar.active = true)))";

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

    private Clock clock = Clock.systemUTC();

    @Inject
    private CalendarAccessService calendarAccessService;

    @Inject
    private CalendarService calendarService;

    @Inject
    private CalendarMembershipService calendarMembershipService;

    @Inject
    private InvitationPolicy invitationPolicy;

    @Inject
    private TokenService tokenService;

    @Inject
    private AuditService auditService;

    @Inject
    private RegistrationInvitationConfiguration registrationInvitationConfiguration;

    public Invitation createRegistrationInvitation(ApplicationUser actingUser) {
        ApplicationUser lockedActingUser = requireActiveUserForInvitationCreation(actingUser);
        OffsetDateTime currentTime = OffsetDateTime.now(clock);
        requireAccountInvitationIssuanceAvailable(lockedActingUser, currentTime);
        requireRegistrationInvitationCapacity(lockedActingUser, currentTime);
        Invitation invitation = createInvitation(lockedActingUser, null, null, currentTime);
        auditService.record(lockedActingUser, null, "app_invitation", invitation.getId(), "created", "Registration invitation created.");
        return invitation;
    }

    public Invitation createCalendarEditorInvitation(ApplicationUser actingUser, Long calendarId) {
        requireActiveUser(actingUser);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        Calendar calendar = requireActiveCalendarForInvitationCreation(calendarId);
        ApplicationUser lockedActingUser = requireActiveUserForInvitationCreation(actingUser);
        calendarAccessService.requireCanEdit(lockedActingUser, calendarId);
        OffsetDateTime currentTime = OffsetDateTime.now(clock);
        requireAccountInvitationIssuanceAvailable(lockedActingUser, currentTime);
        requireCalendarInvitationIssuanceAvailable(calendar, currentTime);
        Invitation invitation = createInvitation(lockedActingUser, calendar, CalendarRole.EDITOR, currentTime);
        auditService.record(lockedActingUser, calendar, "app_invitation", invitation.getId(), "created", "Calendar editor invitation created.");
        return invitation;
    }

    public long captureInvitationSnapshot(ApplicationUser actingUser) {
        requireActiveUser(actingUser);
        InvitationVisibility invitationVisibility = invitationVisibility(actingUser);
        Long maximumInvitationId = bindInvitationVisibility(
                        entityManager.createQuery(
                                "select coalesce(max(invitation.id), 0) from Invitation invitation where "
                                        + invitationVisibility.predicate(),
                                Long.class),
                        invitationVisibility)
                .getSingleResult();
        return maximumInvitationId;
    }

    public long countInvitations(ApplicationUser actingUser, long snapshotMaximumInvitationId) {
        requireActiveUser(actingUser);
        requireValidSnapshotMaximumInvitationId(snapshotMaximumInvitationId);
        InvitationVisibility invitationVisibility = invitationVisibility(actingUser);
        return bindInvitationVisibility(
                        entityManager.createQuery(
                                "select count(invitation) from Invitation invitation where "
                                        + invitationVisibility.predicate()
                                        + " and invitation.id <= :snapshotMaximumInvitationId",
                                Long.class),
                        invitationVisibility)
                .setParameter("snapshotMaximumInvitationId", snapshotMaximumInvitationId)
                .getSingleResult();
    }

    public List<InvitationSummary> listInvitations(
            ApplicationUser actingUser,
            long snapshotMaximumInvitationId,
            int firstResult,
            int maximumResults,
            OffsetDateTime currentTime) {
        requireActiveUser(actingUser);
        requireValidSnapshotMaximumInvitationId(snapshotMaximumInvitationId);
        requireValidInvitationPage(firstResult, maximumResults);
        if (currentTime == null) {
            throw new IllegalArgumentException("Current time is required.");
        }
        InvitationVisibility invitationVisibility = invitationVisibility(actingUser);
        List<Invitation> invitationPage = bindInvitationVisibility(
                        entityManager.createQuery(
                                "select invitation from Invitation invitation "
                                        + "where "
                                        + invitationVisibility.predicate()
                                        + " and invitation.id <= :snapshotMaximumInvitationId"
                                        + " order by invitation.createdAt desc, invitation.id desc",
                                Invitation.class),
                        invitationVisibility)
                .setParameter("snapshotMaximumInvitationId", snapshotMaximumInvitationId)
                .setFirstResult(firstResult)
                .setMaxResults(maximumResults)
                .getResultList();
        Set<Long> availableInvitationIds = availableInvitationIds(
                invitationPage,
                invitationVisibility,
                currentTime);
        return invitationPage.stream()
                .map(invitation -> toInvitationSummary(
                        invitation,
                        availableInvitationIds.contains(invitation.getId())))
                .toList();
    }

    private Set<Long> availableInvitationIds(
            List<Invitation> invitationPage,
            InvitationVisibility invitationVisibility,
            OffsetDateTime currentTime) {
        if (invitationPage.isEmpty()) {
            return Set.of();
        }
        List<Long> pageInvitationIds = invitationPage.stream()
                .map(Invitation::getId)
                .toList();
        List<Long> availableInvitationIds = bindInvitationVisibility(
                        entityManager.createQuery(
                                "select invitation.id from Invitation invitation "
                                        + "where invitation.id in :pageInvitationIds "
                                        + "and ("
                                        + invitationVisibility.predicate()
                                        + ") and "
                                        + USABLE_INVITATION_PREDICATE,
                                Long.class),
                        invitationVisibility)
                .setParameter("pageInvitationIds", pageInvitationIds)
                .setParameter("currentTime", currentTime)
                .setParameter("editorRole", CalendarRole.EDITOR)
                .getResultList();
        return new HashSet<>(availableInvitationIds);
    }

    private InvitationSummary toInvitationSummary(
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

    public void revokeInvitation(ApplicationUser actingUser, Long invitationId) {
        requireActiveUser(actingUser);
        Invitation invitation = requireInvitationForUpdate(invitationId);
        requireCanRevokeInvitation(actingUser, invitation);
        OffsetDateTime currentTime = OffsetDateTime.now(clock);
        InvitationStatus status = invitationPolicy.status(
                invitation.getRevokedAt(), invitation.getAcceptedAt(), invitation.getExpiresAt(), currentTime);
        switch (status) {
            case AVAILABLE -> {
                invitation.setRevokedAt(currentTime);
                auditService.record(actingUser, invitation.getCalendar(), "app_invitation", invitation.getId(), "revoked", "Invitation revoked.");
            }
            case ACCEPTED -> throw new ValidationException("Invitation has already been accepted.");
            case EXPIRED -> throw new ValidationException("Invitation is expired.");
            case REVOKED -> {
                // Revocation is idempotent for an already-revoked invitation.
            }
        }
    }

    public RegistrationAdmission requireAdmission(String invitationToken) {
        return resolveAdmission(invitationToken, false);
    }

    public InvitationAdmissionPreview previewAdmission(String invitationToken) {
        String normalizedInvitationToken = InvitationToken.normalize(invitationToken);
        if (!InvitationToken.isValidCandidate(normalizedInvitationToken)) {
            return InvitationAdmissionPreview.unavailable();
        }

        try {
            Optional<Invitation> invitation = findInvitationByToken(normalizedInvitationToken);
            if (invitation.isPresent()) {
                Invitation availableInvitation = admissionForInvitation(invitation.get(), false).invitation();
                if (availableInvitation.getCalendar() == null) {
                    return InvitationAdmissionPreview.registration(availableInvitation.getExpiresAt());
                }
                return InvitationAdmissionPreview.calendarEditor(
                        availableInvitation.getCalendar().getName(),
                        availableInvitation.getExpiresAt());
            }

            requireBootstrapAdmissionAvailable(normalizedInvitationToken);
            return InvitationAdmissionPreview.registration(null);
        } catch (ValidationException exception) {
            return InvitationAdmissionPreview.unavailable();
        }
    }

    public RegistrationAdmission claimRegistrationAdmission(String invitationToken) {
        return resolveAdmission(invitationToken, true);
    }

    private RegistrationAdmission resolveAdmission(String invitationToken, boolean claimBootstrapAdmission) {
        String normalizedInvitationToken = InvitationToken.normalize(invitationToken);
        if (!InvitationToken.isValidCandidate(normalizedInvitationToken)) {
            throw invalidInvitationException();
        }

        return findInvitationByTokenForUpdate(normalizedInvitationToken)
                .map(invitation -> admissionForInvitation(invitation, true))
                .orElseGet(() -> bootstrapAdmission(normalizedInvitationToken, claimBootstrapAdmission));
    }

    public Invitation acceptInvitation(String invitationToken, ApplicationUser acceptingUser) {
        RegistrationAdmission admission = requireAdmission(invitationToken);
        if (admission.bootstrap()
                || admission.invitation() == null
                || admission.invitation().getCalendar() == null) {
            throw invalidInvitationException();
        }
        acceptAdmission(admission, acceptingUser);
        return admission.invitation();
    }

    public void acceptAdmission(RegistrationAdmission admission, ApplicationUser acceptingUser) {
        if (acceptingUser == null || acceptingUser.getId() == null || !acceptingUser.isActive()) {
            throw new ValidationException("An active user is required to accept an invitation.");
        }
        if (admission.bootstrap()) {
            return;
        }

        Invitation invitation = admission.invitation();
        if (invitation.getCalendar() != null) {
            if (!invitation.getCalendar().isActive()) {
                throw invalidInvitationException();
            }
            if (calendarMembershipService.grantMembershipFromAcceptedInvitation(
                    invitation.getCalendar(),
                    invitation.getCreatedByUser(),
                    acceptingUser,
                    invitation.getRole()).isEmpty()) {
                throw invalidInvitationException();
            }
        }

        invitation.setAcceptedByUser(acceptingUser);
        invitation.setAcceptedAt(OffsetDateTime.now(clock));
        auditService.record(acceptingUser, invitation.getCalendar(), "app_invitation", invitation.getId(), "accepted", "Invitation accepted.");
    }

    private Invitation createInvitation(
            ApplicationUser actingUser,
            Calendar calendar,
            CalendarRole role,
            OffsetDateTime createdAt) {
        invitationPolicy.requireValidScope(calendar, role);

        Invitation invitation = new Invitation();
        invitation.setCalendar(calendar);
        invitation.setInvitationToken(generateInvitationToken());
        invitation.setRole(role);
        invitation.setCreatedByUser(actingUser);
        invitation.setExpiresAt(invitationPolicy.expirationFor(createdAt));
        invitation.setCreatedAt(createdAt);
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }

    private RegistrationAdmission admissionForInvitation(
            Invitation invitation,
            boolean lockRegistrationInvitationCreator) {
        try {
            invitationPolicy.requireAvailable(
                    invitation.getRevokedAt(),
                    invitation.getAcceptedAt(),
                    invitation.getExpiresAt(),
                    OffsetDateTime.now(clock));
            invitationPolicy.requireValidScope(invitation.getCalendar(), invitation.getRole());
        } catch (ValidationException exception) {
            throw invalidInvitationException();
        }
        if (!invitationCreatorCanStillAuthorizeAdmission(
                invitation,
                lockRegistrationInvitationCreator)) {
            throw invalidInvitationException();
        }
        return new RegistrationAdmission(invitation, false);
    }

    private RegistrationAdmission bootstrapAdmission(String invitationToken, boolean claimAdmission) {
        if (!matchesBootstrapInvitationToken(invitationToken)) {
            throw invalidInvitationException();
        }

        RegistrationBootstrapState bootstrapState = requireBootstrapStateForUpdate();
        if (bootstrapState.getConsumedAt() != null || anyUserHasEverExisted()) {
            throw invalidInvitationException();
        }
        if (claimAdmission) {
            bootstrapState.setConsumedAt(OffsetDateTime.now(clock));
        }
        return new RegistrationAdmission(null, true);
    }

    private void requireBootstrapAdmissionAvailable(String invitationToken) {
        if (!matchesBootstrapInvitationToken(invitationToken)) {
            throw invalidInvitationException();
        }

        RegistrationBootstrapState bootstrapState = requireBootstrapState();
        if (bootstrapState.getConsumedAt() != null || anyUserHasEverExisted()) {
            throw invalidInvitationException();
        }
    }

    private Invitation requireInvitationForUpdate(Long invitationId) {
        if (invitationId == null) {
            throw new NotFoundException("Invitation was not found.");
        }

        try {
            return entityManager
                    .createQuery(
                            "select invitation from Invitation invitation "
                                    + "where invitation.id = :invitationId",
                            Invitation.class)
                    .setParameter("invitationId", invitationId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new NotFoundException("Invitation was not found.");
        }
    }

    private Optional<Invitation> findInvitationByTokenForUpdate(String invitationToken) {
        try {
            TypedQuery<Invitation> query = entityManager
                    .createQuery(
                            "select invitation from Invitation invitation "
                                    + "where invitation.invitationToken = :invitationToken",
                            Invitation.class)
                    .setParameter("invitationToken", invitationToken);
            return Optional.of(query.setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private Optional<Invitation> findInvitationByToken(String invitationToken) {
        try {
            return Optional.of(entityManager
                    .createQuery(
                            "select invitation from Invitation invitation "
                                    + "where invitation.invitationToken = :invitationToken",
                            Invitation.class)
                    .setParameter("invitationToken", invitationToken)
                    .getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private InvitationVisibility invitationVisibility(ApplicationUser actingUser) {
        List<Long> administeredCalendarIds = entityManager
                .createQuery(
                        "select calendarMembership.calendar.id "
                                + "from CalendarMembership calendarMembership "
                                + "where calendarMembership.user.id = :actingUserId "
                                + "and calendarMembership.role = :adminRole "
                                + "and calendarMembership.active = true "
                                + "and calendarMembership.user.active = true "
                                + "and calendarMembership.calendar.active = true",
                        Long.class)
                .setParameter("actingUserId", actingUser.getId())
                .setParameter("adminRole", CalendarRole.ADMIN)
                .getResultList();
        return new InvitationVisibility(actingUser.getId(), administeredCalendarIds);
    }

    private <T> TypedQuery<T> bindInvitationVisibility(
            TypedQuery<T> query,
            InvitationVisibility invitationVisibility) {
        TypedQuery<T> boundQuery = query.setParameter(
                "actingUserId",
                invitationVisibility.actingUserId());
        if (!invitationVisibility.administeredCalendarIds().isEmpty()) {
            boundQuery.setParameter(
                    "administeredCalendarIds",
                    invitationVisibility.administeredCalendarIds());
            boundQuery.setParameter("adminRole", CalendarRole.ADMIN);
        }
        return boundQuery;
    }

    private record InvitationVisibility(
            Long actingUserId,
            List<Long> administeredCalendarIds) {
        private InvitationVisibility {
            administeredCalendarIds = List.copyOf(administeredCalendarIds);
        }

        private String predicate() {
            if (administeredCalendarIds.isEmpty()) {
                return CREATOR_INVITATION_PREDICATE;
            }
            return "(" + CREATOR_INVITATION_PREDICATE
                    + " or ("
                    + CURRENT_ADMIN_INVITATION_PREDICATE
                    + "))";
        }
    }

    private void requireValidInvitationPage(int firstResult, int maximumResults) {
        if (firstResult < 0) {
            throw new IllegalArgumentException("The first invitation result cannot be negative.");
        }
        if (maximumResults < 1 || maximumResults > MAXIMUM_INVITATIONS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "The invitation page size must be between 1 and "
                            + MAXIMUM_INVITATIONS_PER_PAGE
                            + ".");
        }
    }

    private void requireValidSnapshotMaximumInvitationId(long snapshotMaximumInvitationId) {
        if (snapshotMaximumInvitationId < 0) {
            throw new IllegalArgumentException("The invitation snapshot identifier cannot be negative.");
        }
    }

    private ApplicationUser requireActiveUserForInvitationCreation(ApplicationUser actingUser) {
        requireActiveUser(actingUser);
        ApplicationUser lockedActingUser = entityManager.find(
                ApplicationUser.class,
                actingUser.getId(),
                LockModeType.PESSIMISTIC_WRITE);
        requireActiveUser(lockedActingUser);
        return lockedActingUser;
    }

    private Calendar requireActiveCalendarForInvitationCreation(Long calendarId) {
        if (calendarId == null) {
            throw new NotFoundException("Calendar was not found.");
        }
        Calendar calendar = entityManager.find(
                Calendar.class,
                calendarId,
                LockModeType.PESSIMISTIC_WRITE);
        if (calendar == null || !calendar.isActive()) {
            throw new NotFoundException("Calendar was not found.");
        }
        return calendar;
    }

    private void requireAccountInvitationIssuanceAvailable(
            ApplicationUser actingUser,
            OffsetDateTime currentTime) {
        Long recentInvitationCount = entityManager
                .createQuery(
                        "select count(invitation) from Invitation invitation "
                                + "where invitation.createdByUser.id = :actingUserId "
                                + "and invitation.createdAt >= :rollingWindowStart",
                        Long.class)
                .setParameter("actingUserId", actingUser.getId())
                .setParameter(
                        "rollingWindowStart",
                        currentTime.minus(INVITATION_ISSUANCE_ROLLING_WINDOW))
                .getSingleResult();
        if (recentInvitationCount >= MAXIMUM_INVITATIONS_PER_ACCOUNT_IN_ROLLING_WINDOW) {
            throw new ValidationException("Invitation creation limit reached. Try again later.");
        }
    }

    private void requireRegistrationInvitationCapacity(
            ApplicationUser actingUser,
            OffsetDateTime currentTime) {
        Long outstandingInvitationCount = entityManager
                .createQuery(
                        "select count(invitation) from Invitation invitation "
                                + "where invitation.createdByUser.id = :actingUserId "
                                + "and invitation.calendar is null "
                                + "and invitation.role is null "
                                + "and invitation.acceptedAt is null "
                                + "and invitation.revokedAt is null "
                                + "and invitation.expiresAt > :currentTime",
                        Long.class)
                .setParameter("actingUserId", actingUser.getId())
                .setParameter("currentTime", currentTime)
                .getSingleResult();
        if (outstandingInvitationCount
                >= MAXIMUM_OUTSTANDING_REGISTRATION_INVITATIONS_PER_ACCOUNT) {
            throw new ValidationException(
                    "Too many active registration invitations. Revoke one before creating another.");
        }
    }

    private void requireCalendarInvitationIssuanceAvailable(
            Calendar calendar,
            OffsetDateTime currentTime) {
        Long outstandingInvitationCount = entityManager
                .createQuery(
                        "select count(invitation) from Invitation invitation "
                                + "where invitation.calendar.id = :calendarId "
                                + "and invitation.role = :editorRole "
                                + "and invitation.acceptedAt is null "
                                + "and invitation.revokedAt is null "
                                + "and invitation.expiresAt > :currentTime",
                        Long.class)
                .setParameter("calendarId", calendar.getId())
                .setParameter("editorRole", CalendarRole.EDITOR)
                .setParameter("currentTime", currentTime)
                .getSingleResult();
        if (outstandingInvitationCount
                >= MAXIMUM_OUTSTANDING_EDITOR_INVITATIONS_PER_CALENDAR) {
            throw new ValidationException(
                    "Too many outstanding editor invitations. Revoke one before creating another.");
        }

        Long recentInvitationCount = entityManager
                .createQuery(
                        "select count(invitation) from Invitation invitation "
                                + "where invitation.calendar.id = :calendarId "
                                + "and invitation.role = :editorRole "
                                + "and invitation.createdAt >= :rollingWindowStart",
                        Long.class)
                .setParameter("calendarId", calendar.getId())
                .setParameter("editorRole", CalendarRole.EDITOR)
                .setParameter(
                        "rollingWindowStart",
                        currentTime.minus(INVITATION_ISSUANCE_ROLLING_WINDOW))
                .getSingleResult();
        if (recentInvitationCount
                >= MAXIMUM_EDITOR_INVITATIONS_PER_CALENDAR_IN_ROLLING_WINDOW) {
            throw new ValidationException("Calendar invitation creation limit reached. Try again later.");
        }
    }

    private RegistrationBootstrapState requireBootstrapStateForUpdate() {
        try {
            return entityManager
                    .createQuery(
                            "select bootstrapState from RegistrationBootstrapState bootstrapState "
                                    + "where bootstrapState.singletonId = :singletonId",
                            RegistrationBootstrapState.class)
                    .setParameter("singletonId", RegistrationBootstrapState.SINGLETON_ID)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new IllegalStateException("Registration bootstrap state is missing.", exception);
        }
    }

    private RegistrationBootstrapState requireBootstrapState() {
        try {
            return entityManager
                    .createQuery(
                            "select bootstrapState from RegistrationBootstrapState bootstrapState "
                                    + "where bootstrapState.singletonId = :singletonId",
                            RegistrationBootstrapState.class)
                    .setParameter("singletonId", RegistrationBootstrapState.SINGLETON_ID)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new IllegalStateException("Registration bootstrap state is missing.", exception);
        }
    }

    private boolean anyUserHasEverExisted() {
        Long userCount = entityManager
                .createQuery("select count(applicationUser) from ApplicationUser applicationUser", Long.class)
                .getSingleResult();
        return userCount > 0;
    }

    private boolean invitationCreatorCanStillAuthorizeAdmission(
            Invitation invitation,
            boolean lockRegistrationInvitationCreator) {
        boolean registrationInvitation = invitation.getCalendar() == null;
        Optional<ApplicationUser> activeInvitationCreator = findActiveInvitationCreator(
                invitation.getCreatedByUser(),
                registrationInvitation && lockRegistrationInvitationCreator);
        if (activeInvitationCreator.isEmpty()) {
            return false;
        }
        if (registrationInvitation) {
            return true;
        }
        if (!invitation.getCalendar().isActive()) {
            return false;
        }

        try {
            calendarAccessService.requireCanEdit(activeInvitationCreator.get(), invitation.getCalendar().getId());
            return true;
        } catch (AuthorizationException exception) {
            return false;
        }
    }

    private Optional<ApplicationUser> findActiveInvitationCreator(
            ApplicationUser invitationCreator,
            boolean lockCreator) {
        if (invitationCreator == null || invitationCreator.getId() == null) {
            return Optional.empty();
        }

        try {
            TypedQuery<ApplicationUser> activeCreatorQuery = entityManager
                    .createQuery(
                            "select applicationUser from ApplicationUser applicationUser "
                                    + "where applicationUser.id = :invitationCreatorId "
                                    + "and applicationUser.active = true",
                            ApplicationUser.class)
                    .setParameter("invitationCreatorId", invitationCreator.getId());
            if (lockCreator) {
                activeCreatorQuery.setLockMode(LockModeType.PESSIMISTIC_READ);
            }
            return Optional.of(activeCreatorQuery.getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private void requireCanRevokeInvitation(ApplicationUser actingUser, Invitation invitation) {
        if (invitation.getCreatedByUser() != null
                && actingUser.getId().equals(invitation.getCreatedByUser().getId())) {
            return;
        }
        if (invitation.getCalendar() != null) {
            calendarService.requireActiveCalendarForChildMutation(invitation.getCalendar().getId());
            calendarAccessService.requireCanAdminister(actingUser, invitation.getCalendar().getId());
            return;
        }
        throw new AuthorizationException("Only the invitation creator can revoke this invitation.");
    }

    private void requireActiveUser(ApplicationUser actingUser) {
        if (actingUser == null || actingUser.getId() == null || !actingUser.isActive()) {
            throw new AuthorizationException("Sign-in is required.");
        }
    }

    /**
     * Uniqueness is guaranteed by the unique {@code app_invitation.invite_token} constraint, not by
     * reading the column first: a read cannot see a token another transaction is about to insert,
     * so a pre-check would still leave the constraint as the only real guarantee.
     */
    private String generateInvitationToken() {
        return tokenService.generateInvitationToken();
    }

    private boolean matchesBootstrapInvitationToken(String invitationToken) {
        return registrationInvitationConfiguration.matchesBootstrapInvitationToken(invitationToken);
    }

    private ValidationException invalidInvitationException() {
        return new ValidationException("Invitation is invalid or no longer available.");
    }
}
