package app.invitation;

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
import java.util.List;
import java.util.Optional;

@Stateless
public class InvitationService {
    private static final Duration INVITATION_LIFETIME = Duration.ofDays(7);

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
    private TokenService tokenService;

    @Inject
    private RegistrationInvitationConfiguration registrationInvitationConfiguration;

    public Invitation createRegistrationInvitation(ApplicationUser actingUser) {
        return createInvitation(requireUser(actingUser), null);
    }

    public Invitation createCalendarEditorInvitation(ApplicationUser actingUser, Long calendarId) {
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        Calendar calendar = calendarService.requireCalendarForChildMutation(calendarId);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        return createInvitation(requireUser(actingUser), calendar);
    }

    public List<InvitationSummary> listOutstandingInvitations(ApplicationUser actingUser) {
        ApplicationUser user = requireUser(actingUser);
        OffsetDateTime currentTime = OffsetDateTime.now(clock);
        deleteExpiredInvitations(currentTime);
        return entityManager
                .createQuery(
                        "select new app.invitation.InvitationSummary("
                                + "invitation.id, invitation.invitationToken, "
                                + "calendar.name, invitation.createdAt, invitation.expiresAt) "
                                + "from Invitation invitation "
                                + "left join invitation.calendar calendar "
                                + "where invitation.createdByUser.id = :userId "
                                + "or (invitation.calendar is not null and exists ("
                                + "select membership.calendar.id from CalendarMembership membership "
                                + "where membership.calendar.id = invitation.calendar.id "
                                + "and membership.user.id = :userId "
                                + "and membership.role = :adminRole)) "
                                + "order by invitation.createdAt desc, invitation.id desc",
                        InvitationSummary.class)
                .setParameter("userId", user.getId())
                .setParameter("adminRole", CalendarRole.ADMIN)
                .getResultList();
    }

    public void revokeInvitation(ApplicationUser actingUser, Long invitationId) {
        ApplicationUser user = requireUser(actingUser);
        Invitation invitation = requireInvitationForUpdate(invitationId);
        if (!user.getId().equals(invitation.getCreatedByUser().getId())) {
            if (invitation.getCalendar() == null) {
                throw new AuthorizationException("Only the invitation creator can revoke this invitation.");
            }
            calendarAccessService.requireCanAdminister(user, invitation.getCalendar().getId());
        }
        entityManager.remove(invitation);
    }

    public InvitationAdmissionPreview previewAdmission(String invitationToken) {
        String normalizedToken = normalizeValidToken(invitationToken);
        if (normalizedToken == null) {
            return InvitationAdmissionPreview.unavailable();
        }

        Optional<Invitation> invitation = findInvitationByToken(normalizedToken, false);
        if (invitation.isPresent() && invitationIsUsable(invitation.get())) {
            Invitation availableInvitation = invitation.get();
            return availableInvitation.getCalendar() == null
                    ? InvitationAdmissionPreview.registration(availableInvitation.getExpiresAt())
                    : InvitationAdmissionPreview.calendarEditor(
                            availableInvitation.getCalendar().getName(),
                            availableInvitation.getExpiresAt());
        }
        return bootstrapAdmissionAvailable(normalizedToken)
                ? InvitationAdmissionPreview.registration(null)
                : InvitationAdmissionPreview.unavailable();
    }

    public RegistrationAdmission claimRegistrationAdmission(String invitationToken) {
        String normalizedToken = requireValidToken(invitationToken);
        Optional<Invitation> invitation = findInvitationByToken(normalizedToken, true);
        if (invitation.isPresent()) {
            requireUsableInvitation(invitation.get());
            return new RegistrationAdmission(invitation.get(), false);
        }

        RegistrationBootstrapState bootstrapState = requireBootstrapState(true);
        if (!registrationInvitationConfiguration.matchesBootstrapInvitationToken(normalizedToken)
                || bootstrapState.getConsumedAt() != null) {
            throw invalidInvitation();
        }
        bootstrapState.setConsumedAt(OffsetDateTime.now(clock));
        return new RegistrationAdmission(null, true);
    }

    public Invitation acceptInvitation(String invitationToken, ApplicationUser acceptingUser) {
        String normalizedToken = requireValidToken(invitationToken);
        Invitation invitation = findInvitationByToken(normalizedToken, true)
                .orElseThrow(this::invalidInvitation);
        if (invitation.getCalendar() == null) {
            throw invalidInvitation();
        }
        requireUsableInvitation(invitation);
        acceptAdmission(new RegistrationAdmission(invitation, false), acceptingUser);
        return invitation;
    }

    public void acceptAdmission(RegistrationAdmission admission, ApplicationUser acceptingUser) {
        ApplicationUser user = requireUserForAdmission(acceptingUser);
        if (admission == null || admission.bootstrap()) {
            return;
        }

        Invitation invitation = admission.invitation();
        if (invitation == null || !entityManager.contains(invitation)) {
            throw invalidInvitation();
        }
        if (invitation.getCalendar() != null
                && calendarMembershipService.grantEditorMembershipFromInvitation(
                                invitation.getCalendar(), invitation.getCreatedByUser(), user)
                        .isEmpty()) {
            throw invalidInvitation();
        }
        entityManager.remove(invitation);
    }

    private Invitation createInvitation(ApplicationUser actingUser, Calendar calendar) {
        OffsetDateTime createdAt = OffsetDateTime.now(clock);
        Invitation invitation = new Invitation();
        invitation.setCalendar(calendar);
        invitation.setInvitationToken(tokenService.generateInvitationToken());
        invitation.setCreatedByUser(actingUser);
        invitation.setCreatedAt(createdAt);
        invitation.setExpiresAt(createdAt.plus(INVITATION_LIFETIME));
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }

    private void deleteExpiredInvitations(OffsetDateTime currentTime) {
        entityManager
                .createQuery("delete from Invitation invitation where invitation.expiresAt <= :currentTime")
                .setParameter("currentTime", currentTime)
                .executeUpdate();
    }

    private boolean invitationIsUsable(Invitation invitation) {
        if (!invitation.getExpiresAt().isAfter(OffsetDateTime.now(clock))) {
            return false;
        }
        return invitation.getCalendar() == null
                || calendarAccessService.findRole(
                                invitation.getCreatedByUser(), invitation.getCalendar().getId())
                        .isPresent();
    }

    private void requireUsableInvitation(Invitation invitation) {
        if (!invitationIsUsable(invitation)) {
            throw invalidInvitation();
        }
    }

    private boolean bootstrapAdmissionAvailable(String invitationToken) {
        if (!registrationInvitationConfiguration.matchesBootstrapInvitationToken(invitationToken)) {
            return false;
        }
        return requireBootstrapState(false).getConsumedAt() == null;
    }

    private Optional<Invitation> findInvitationByToken(String invitationToken, boolean lock) {
        try {
            TypedQuery<Invitation> query = entityManager
                    .createQuery(
                            "select invitation from Invitation invitation "
                                    + "where invitation.invitationToken = :invitationToken",
                            Invitation.class)
                    .setParameter("invitationToken", invitationToken);
            if (lock) {
                query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            }
            return Optional.of(query.getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private Invitation requireInvitationForUpdate(Long invitationId) {
        if (invitationId == null) {
            throw new NotFoundException("Invitation was not found.");
        }
        Invitation invitation = entityManager.find(
                Invitation.class, invitationId, LockModeType.PESSIMISTIC_WRITE);
        if (invitation == null) {
            throw new NotFoundException("Invitation was not found.");
        }
        return invitation;
    }

    private RegistrationBootstrapState requireBootstrapState(boolean lock) {
        LockModeType lockMode = lock ? LockModeType.PESSIMISTIC_WRITE : LockModeType.NONE;
        RegistrationBootstrapState state = entityManager.find(
                RegistrationBootstrapState.class,
                RegistrationBootstrapState.SINGLETON_ID,
                lockMode);
        if (state == null) {
            throw new IllegalStateException("Registration bootstrap state is missing.");
        }
        return state;
    }

    private ApplicationUser requireUser(ApplicationUser user) {
        if (user == null || user.getId() == null) {
            throw new AuthorizationException("Sign-in is required.");
        }
        ApplicationUser managedUser = entityManager.find(ApplicationUser.class, user.getId());
        if (managedUser == null) {
            throw new AuthorizationException("Sign-in is required.");
        }
        return managedUser;
    }

    private ApplicationUser requireUserForAdmission(ApplicationUser user) {
        try {
            return requireUser(user);
        } catch (AuthorizationException exception) {
            throw new ValidationException("A registered user is required to accept an invitation.");
        }
    }

    private String requireValidToken(String invitationToken) {
        String normalizedToken = normalizeValidToken(invitationToken);
        if (normalizedToken == null) {
            throw invalidInvitation();
        }
        return normalizedToken;
    }

    private String normalizeValidToken(String invitationToken) {
        String normalizedToken = InvitationToken.normalize(invitationToken);
        return InvitationToken.isValidCandidate(normalizedToken) ? normalizedToken : null;
    }

    private ValidationException invalidInvitation() {
        return new ValidationException("Invitation is invalid or no longer available.");
    }
}
