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
import app.user.UserService;
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
    private static final int MAXIMUM_TOKEN_GENERATION_ATTEMPTS = 10;
    private static final String BOOTSTRAP_INVITATION_TOKEN_ENVIRONMENT_VARIABLE = "APP_BOOTSTRAP_INVITE_TOKEN";

    @PersistenceContext(unitName = "calendarPU")
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
    private UserService userService;

    @Inject
    private AuditService auditService;

    private String bootstrapInvitationToken = System.getenv(BOOTSTRAP_INVITATION_TOKEN_ENVIRONMENT_VARIABLE);

    public Invitation createRegistrationInvitation(ApplicationUser actingUser) {
        requireActiveUser(actingUser);
        Invitation invitation = createInvitation(actingUser, null, null, null);
        auditService.record(actingUser, null, "app_invitation", invitation.getId(), "created", "Registration invitation created.");
        return invitation;
    }

    public Invitation createCalendarEditorInvitation(ApplicationUser actingUser, Long calendarId, OffsetDateTime expiresAt) {
        requireActiveUser(actingUser);
        calendarAccessService.requireCanEdit(actingUser, calendarId);
        Calendar calendar = calendarService.requireActiveCalendar(calendarId);
        Invitation invitation = createInvitation(actingUser, calendar, CalendarRole.EDITOR, expiresAt);
        auditService.record(actingUser, calendar, "app_invitation", invitation.getId(), "created", "Calendar editor invitation created.");
        return invitation;
    }

    public List<Invitation> listInvitations(ApplicationUser actingUser) {
        requireActiveUser(actingUser);
        return entityManager
                .createQuery(
                        "select invitation from Invitation invitation "
                                + "left join fetch invitation.calendar "
                                + "where invitation.createdByUser.id = :actingUserId "
                                + "order by invitation.createdAt desc, invitation.id desc",
                        Invitation.class)
                .setParameter("actingUserId", actingUser.getId())
                .setMaxResults(50)
                .getResultList();
    }

    public void revokeInvitation(ApplicationUser actingUser, Long invitationId) {
        requireActiveUser(actingUser);
        Invitation invitation = requireInvitationForUpdate(invitationId);
        requireInvitationCreator(actingUser, invitation);
        OffsetDateTime currentTime = OffsetDateTime.now(ZoneOffset.UTC);
        InvitationStatus status = invitationPolicy.status(
                invitation.getRevokedAt(), invitation.getAcceptedAt(), invitation.getExpiresAt(), currentTime);
        switch (status) {
            case AVAILABLE -> {
                invitation.setRevokedAt(currentTime);
                auditService.record(actingUser, invitation.getCalendar(), "app_invitation", invitation.getId(), "revoked", "Invitation revoked.");
            }
            case USED -> throw new ValidationException("Invitation has already been used.");
            case EXPIRED -> throw new ValidationException("Invitation is expired.");
            case REVOKED -> {
                // Revocation is idempotent for an already-revoked invitation.
            }
        }
    }

    public RegistrationAdmission requireAdmission(String invitationToken) {
        String normalizedInvitationToken = normalizeInvitationToken(invitationToken);
        if (normalizedInvitationToken.isBlank()) {
            throw invalidInvitationException();
        }

        return findInvitationByTokenForUpdate(normalizedInvitationToken)
                .map(this::admissionForInvitation)
                .orElseGet(() -> bootstrapAdmission(normalizedInvitationToken));
    }

    public Invitation acceptInvitation(String invitationToken, ApplicationUser acceptingUser) {
        RegistrationAdmission admission = requireAdmission(invitationToken);
        acceptAdmission(admission, acceptingUser);
        return admission.invitation();
    }

    public void acceptAdmission(RegistrationAdmission admission, ApplicationUser acceptingUser) {
        if (admission.bootstrap()) {
            return;
        }
        if (acceptingUser == null || acceptingUser.getId() == null || !acceptingUser.isActive()) {
            throw new ValidationException("An active user is required to accept an invitation.");
        }

        Invitation invitation = admission.invitation();
        if (invitation.getCalendar() != null) {
            if (!invitation.getCalendar().isActive()) {
                throw invalidInvitationException();
            }
            calendarMembershipService.grantMembershipFromAcceptedInvitation(
                    invitation.getCalendar(),
                    acceptingUser,
                    invitation.getRole());
        }

        invitation.setAcceptedByUser(acceptingUser);
        invitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(acceptingUser, invitation.getCalendar(), "app_invitation", invitation.getId(), "accepted", "Invitation accepted.");
    }

    private Invitation createInvitation(
            ApplicationUser actingUser,
            Calendar calendar,
            CalendarRole role,
            OffsetDateTime expiresAt) {
        invitationPolicy.requireValidScope(calendar, role);

        Invitation invitation = new Invitation();
        invitation.setCalendar(calendar);
        invitation.setInvitationToken(generateUniqueInvitationToken());
        invitation.setRole(role);
        invitation.setCreatedByUser(actingUser);
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }

    private RegistrationAdmission admissionForInvitation(Invitation invitation) {
        invitationPolicy.requireOpen(
                invitation.getRevokedAt(),
                invitation.getAcceptedAt(),
                invitation.getExpiresAt(),
                OffsetDateTime.now(ZoneOffset.UTC));
        invitationPolicy.requireValidScope(invitation.getCalendar(), invitation.getRole());
        return new RegistrationAdmission(invitation, false);
    }

    private RegistrationAdmission bootstrapAdmission(String invitationToken) {
        if (!matchesBootstrapInvitationToken(invitationToken) || userService.hasActiveUsers()) {
            throw invalidInvitationException();
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
                                    + "join fetch invitation.createdByUser "
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

    private void requireInvitationCreator(ApplicationUser actingUser, Invitation invitation) {
        if (invitation.getCreatedByUser() == null || !actingUser.getId().equals(invitation.getCreatedByUser().getId())) {
            throw new AuthorizationException("Only the invitation creator can revoke this invitation.");
        }
    }

    private void requireActiveUser(ApplicationUser actingUser) {
        if (actingUser == null || actingUser.getId() == null || !actingUser.isActive()) {
            throw new AuthorizationException("Sign-in is required.");
        }
    }

    private String generateUniqueInvitationToken() {
        for (int attempt = 0; attempt < MAXIMUM_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String token = tokenService.generateToken();
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
        String configuredBootstrapInvitationToken = normalizeInvitationToken(bootstrapInvitationToken);
        if (configuredBootstrapInvitationToken.isBlank()) {
            return false;
        }

        return MessageDigest.isEqual(
                configuredBootstrapInvitationToken.getBytes(StandardCharsets.UTF_8),
                invitationToken.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeInvitationToken(String invitationToken) {
        if (invitationToken == null) {
            return "";
        }
        return invitationToken.trim();
    }

    private ValidationException invalidInvitationException() {
        return new ValidationException("Invitation is invalid or no longer available.");
    }
}
