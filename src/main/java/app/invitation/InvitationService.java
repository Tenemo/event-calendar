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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Stateless
public class InvitationService {
    static final int MAXIMUM_INVITATIONS_PER_PAGE = 50;

    private static final int MAXIMUM_TOKEN_GENERATION_ATTEMPTS = 10;
    private static final String BOOTSTRAP_INVITATION_TOKEN_ENVIRONMENT_VARIABLE = "APP_BOOTSTRAP_INVITE_TOKEN";
    private static final String VISIBLE_INVITATION_PREDICATE =
            "invitation.createdByUser.id = :actingUserId "
                    + "or exists ("
                    + "select calendarMembership.calendar.id from CalendarMembership calendarMembership "
                    + "where calendarMembership.calendar = invitation.calendar "
                    + "and calendarMembership.user.id = :actingUserId "
                    + "and calendarMembership.role = :adminRole "
                    + "and calendarMembership.active = true "
                    + "and calendarMembership.user.active = true "
                    + "and calendarMembership.calendar.active = true)";

    @PersistenceContext(unitName = "calendarPersistenceUnit")
    private EntityManager entityManager;

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

    private String bootstrapInvitationToken = System.getenv(BOOTSTRAP_INVITATION_TOKEN_ENVIRONMENT_VARIABLE);

    public Invitation createRegistrationInvitation(ApplicationUser actingUser) {
        requireActiveUser(actingUser);
        Invitation invitation = createInvitation(actingUser, null, null);
        auditService.record(actingUser, null, "app_invitation", invitation.getId(), "created", "Registration invitation created.");
        return invitation;
    }

    public Invitation createCalendarEditorInvitation(ApplicationUser actingUser, Long calendarId) {
        requireActiveUser(actingUser);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        Calendar calendar = calendarService.requireActiveCalendarForChildMutation(calendarId);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        Invitation invitation = createInvitation(actingUser, calendar, CalendarRole.EDITOR);
        auditService.record(actingUser, calendar, "app_invitation", invitation.getId(), "created", "Calendar editor invitation created.");
        return invitation;
    }

    public long countInvitations(ApplicationUser actingUser) {
        requireActiveUser(actingUser);
        return bindInvitationVisibility(
                        entityManager.createQuery(
                                "select count(invitation) from Invitation invitation where "
                                        + VISIBLE_INVITATION_PREDICATE,
                                Long.class),
                        actingUser)
                .getSingleResult();
    }

    public List<Invitation> listInvitations(
            ApplicationUser actingUser,
            int firstResult,
            int maximumResults) {
        requireActiveUser(actingUser);
        requireValidInvitationPage(firstResult, maximumResults);
        List<Object[]> invitationRows = bindInvitationVisibility(
                        entityManager.createQuery(
                                "select invitation, case when invitation.acceptedAt is null "
                                        + "and invitation.revokedAt is null "
                                        + "and invitation.expiresAt > :currentTime "
                                        + "then 0 else 1 end as availabilityOrder "
                                        + "from Invitation invitation "
                                        + "where "
                                        + VISIBLE_INVITATION_PREDICATE
                                        + " order by availabilityOrder, "
                                        + "invitation.createdAt desc, invitation.id desc",
                                Object[].class),
                        actingUser)
                .setParameter("currentTime", OffsetDateTime.now(ZoneOffset.UTC))
                .setFirstResult(firstResult)
                .setMaxResults(maximumResults)
                .getResultList();
        return invitationRows.stream()
                .map(invitationRow -> {
                    if (invitationRow.length < 2
                            || !(invitationRow[0] instanceof Invitation invitation)) {
                        throw new IllegalStateException("Invitation query returned an invalid row.");
                    }
                    Calendar invitationCalendar = invitation.getCalendar();
                    if (invitationCalendar != null && invitationCalendar.getName() == null) {
                        throw new IllegalStateException(
                                "Invitation query returned a calendar without a name.");
                    }
                    return invitation;
                })
                .toList();
    }

    public void revokeInvitation(ApplicationUser actingUser, Long invitationId) {
        requireActiveUser(actingUser);
        Invitation invitation = requireInvitationForUpdate(invitationId);
        requireCanRevokeInvitation(actingUser, invitation);
        OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
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

    public RegistrationAdmission claimRegistrationAdmission(String invitationToken) {
        return resolveAdmission(invitationToken, true);
    }

    private RegistrationAdmission resolveAdmission(String invitationToken, boolean claimBootstrapAdmission) {
        String normalizedInvitationToken = InvitationToken.normalize(invitationToken);
        if (!InvitationToken.isValidCandidate(normalizedInvitationToken)) {
            throw invalidInvitationException();
        }

        return findInvitationByTokenForUpdate(normalizedInvitationToken)
                .map(this::admissionForInvitation)
                .orElseGet(() -> bootstrapAdmission(normalizedInvitationToken, claimBootstrapAdmission));
    }

    public Invitation acceptInvitation(String invitationToken, ApplicationUser acceptingUser) {
        RegistrationAdmission admission = requireAdmission(invitationToken);
        if (admission.bootstrap()) {
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
        invitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(acceptingUser, invitation.getCalendar(), "app_invitation", invitation.getId(), "accepted", "Invitation accepted.");
    }

    private Invitation createInvitation(
            ApplicationUser actingUser,
            Calendar calendar,
            CalendarRole role) {
        invitationPolicy.requireValidScope(calendar, role);

        Invitation invitation = new Invitation();
        invitation.setCalendar(calendar);
        invitation.setInvitationToken(generateUniqueInvitationToken());
        invitation.setRole(role);
        invitation.setCreatedByUser(actingUser);
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        invitation.setExpiresAt(invitationPolicy.expirationFor(createdAt));
        invitation.setCreatedAt(createdAt);
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }

    private RegistrationAdmission admissionForInvitation(Invitation invitation) {
        try {
            invitationPolicy.requireOpen(
                    invitation.getRevokedAt(),
                    invitation.getAcceptedAt(),
                    invitation.getExpiresAt(),
                    OffsetDateTime.now(ZoneOffset.UTC));
            invitationPolicy.requireValidScope(invitation.getCalendar(), invitation.getRole());
        } catch (ValidationException exception) {
            throw invalidInvitationException();
        }
        if (!invitationCreatorCanStillAuthorizeAdmission(invitation)) {
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
            bootstrapState.setConsumedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        return new RegistrationAdmission(null, true);
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

    private <T> TypedQuery<T> bindInvitationVisibility(
            TypedQuery<T> query,
            ApplicationUser actingUser) {
        return query
                .setParameter("actingUserId", actingUser.getId())
                .setParameter("adminRole", CalendarRole.ADMIN);
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

    private boolean anyUserHasEverExisted() {
        Long userCount = entityManager
                .createQuery("select count(applicationUser) from ApplicationUser applicationUser", Long.class)
                .getSingleResult();
        return userCount > 0;
    }

    private boolean invitationCreatorCanStillAuthorizeAdmission(Invitation invitation) {
        Optional<ApplicationUser> activeInvitationCreator = findActiveInvitationCreator(invitation.getCreatedByUser());
        if (activeInvitationCreator.isEmpty()) {
            return false;
        }
        if (invitation.getCalendar() == null) {
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

    private Optional<ApplicationUser> findActiveInvitationCreator(ApplicationUser invitationCreator) {
        if (invitationCreator == null || invitationCreator.getId() == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(entityManager
                    .createQuery(
                            "select applicationUser from ApplicationUser applicationUser "
                                    + "where applicationUser.id = :invitationCreatorId "
                                    + "and applicationUser.active = true",
                            ApplicationUser.class)
                    .setParameter("invitationCreatorId", invitationCreator.getId())
                    .setLockMode(LockModeType.PESSIMISTIC_READ)
                    .getSingleResult());
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

    private String generateUniqueInvitationToken() {
        for (int attempt = 0; attempt < MAXIMUM_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String token = tokenService.generateInvitationToken();
            Long existingCount = entityManager
                    .createQuery(
                            "select count(invitation) from Invitation invitation "
                                    + "where invitation.invitationToken = :token",
                            Long.class)
                    .setParameter("token", token)
                    .getSingleResult();
            if (existingCount == 0) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique invitation token.");
    }

    private boolean matchesBootstrapInvitationToken(String invitationToken) {
        String configuredBootstrapInvitationToken = InvitationToken.normalize(bootstrapInvitationToken);
        if (!InvitationToken.isValidCandidate(configuredBootstrapInvitationToken)) {
            return false;
        }

        return MessageDigest.isEqual(
                configuredBootstrapInvitationToken.getBytes(StandardCharsets.UTF_8),
                invitationToken.getBytes(StandardCharsets.UTF_8));
    }

    private ValidationException invalidInvitationException() {
        return new ValidationException("Invitation is invalid or no longer available.");
    }
}
