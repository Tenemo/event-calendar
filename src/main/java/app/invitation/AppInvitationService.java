package app.invitation;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.user.AppUser;
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
public class AppInvitationService {
    private static final int MAXIMUM_TOKEN_GENERATION_ATTEMPTS = 10;
    private static final String BOOTSTRAP_INVITE_TOKEN_ENVIRONMENT_VARIABLE = "APP_BOOTSTRAP_INVITE_TOKEN";

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

    private String bootstrapInviteToken = System.getenv(BOOTSTRAP_INVITE_TOKEN_ENVIRONMENT_VARIABLE);

    public AppInvitation createAppInvitation(AppUser actor) {
        requireActiveUser(actor);
        AppInvitation invitation = createInvitation(actor, null, null, null);
        auditService.record(actor, null, "app_invitation", invitation.getId(), "created", "App invitation created.");
        return invitation;
    }

    public AppInvitation createCalendarEditorInvitation(AppUser actor, Long calendarId, OffsetDateTime expiresAt) {
        requireActiveUser(actor);
        calendarAccessService.requireCanEdit(actor, calendarId);
        Calendar calendar = calendarService.requireActiveCalendar(calendarId);
        AppInvitation invitation = createInvitation(actor, calendar, CalendarRole.EDITOR, expiresAt);
        auditService.record(actor, calendar, "app_invitation", invitation.getId(), "created", "Calendar editor invitation created.");
        return invitation;
    }

    public List<AppInvitation> listInvitations(AppUser actor) {
        requireActiveUser(actor);
        return entityManager
                .createQuery(
                        "select invitation from AppInvitation invitation "
                                + "left join fetch invitation.calendar "
                                + "where invitation.createdByUser.id = :actorUserId "
                                + "order by invitation.createdAt desc, invitation.id desc",
                        AppInvitation.class)
                .setParameter("actorUserId", actor.getId())
                .setMaxResults(50)
                .getResultList();
    }

    public void revokeInvitation(AppUser actor, Long invitationId) {
        requireActiveUser(actor);
        AppInvitation invitation = requireInvitationForUpdate(invitationId);
        requireInvitationCreator(actor, invitation);
        if (invitation.getAcceptedAt() != null) {
            throw new ValidationException("Invitation has already been used.");
        }
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
            auditService.record(actor, invitation.getCalendar(), "app_invitation", invitation.getId(), "revoked", "Invitation revoked.");
        }
    }

    public RegistrationAdmission requireAdmission(String inviteToken) {
        String normalizedInviteToken = normalizeInviteToken(inviteToken);
        if (normalizedInviteToken.isBlank()) {
            throw invalidInvitationException();
        }

        return findInvitationByTokenForUpdate(normalizedInviteToken)
                .map(this::admissionForInvitation)
                .orElseGet(() -> bootstrapAdmission(normalizedInviteToken));
    }

    public AppInvitation acceptInvitation(String inviteToken, AppUser acceptingUser) {
        RegistrationAdmission admission = requireAdmission(inviteToken);
        acceptAdmission(admission, acceptingUser);
        return admission.invitation();
    }

    public void acceptAdmission(RegistrationAdmission admission, AppUser acceptingUser) {
        if (admission.bootstrap()) {
            return;
        }
        if (acceptingUser == null || acceptingUser.getId() == null || !acceptingUser.isActive()) {
            throw new ValidationException("An active user is required to accept an invitation.");
        }

        AppInvitation invitation = admission.invitation();
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

    private AppInvitation createInvitation(
            AppUser actor,
            Calendar calendar,
            CalendarRole role,
            OffsetDateTime expiresAt) {
        invitationPolicy.requireValidScope(calendar, role);

        AppInvitation invitation = new AppInvitation();
        invitation.setCalendar(calendar);
        invitation.setInviteToken(generateUniqueInviteToken());
        invitation.setRole(role);
        invitation.setCreatedByUser(actor);
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entityManager.persist(invitation);
        entityManager.flush();
        return invitation;
    }

    private RegistrationAdmission admissionForInvitation(AppInvitation invitation) {
        invitationPolicy.requireOpen(
                invitation.getRevokedAt(),
                invitation.getAcceptedAt(),
                invitation.getExpiresAt(),
                OffsetDateTime.now(ZoneOffset.UTC));
        invitationPolicy.requireValidScope(invitation.getCalendar(), invitation.getRole());
        return new RegistrationAdmission(invitation, false);
    }

    private RegistrationAdmission bootstrapAdmission(String inviteToken) {
        if (!matchesBootstrapInviteToken(inviteToken) || userService.hasActiveUsers()) {
            throw invalidInvitationException();
        }
        return new RegistrationAdmission(null, true);
    }

    private AppInvitation requireInvitationForUpdate(Long invitationId) {
        if (invitationId == null) {
            throw new NotFoundException("Invitation was not found.");
        }

        try {
            return entityManager
                    .createQuery(
                            "select invitation from AppInvitation invitation "
                                    + "join fetch invitation.createdByUser "
                                    + "where invitation.id = :invitationId",
                            AppInvitation.class)
                    .setParameter("invitationId", invitationId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new NotFoundException("Invitation was not found.");
        }
    }

    private Optional<AppInvitation> findInvitationByTokenForUpdate(String inviteToken) {
        try {
            TypedQuery<AppInvitation> query = entityManager
                    .createQuery(
                            "select invitation from AppInvitation invitation "
                                    + "where invitation.inviteToken = :inviteToken",
                            AppInvitation.class)
                    .setParameter("inviteToken", inviteToken);
            return Optional.of(query.setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult());
        } catch (NoResultException exception) {
            return Optional.empty();
        }
    }

    private void requireInvitationCreator(AppUser actor, AppInvitation invitation) {
        if (invitation.getCreatedByUser() == null || !actor.getId().equals(invitation.getCreatedByUser().getId())) {
            throw new AuthorizationException("Only the invitation creator can revoke this invitation.");
        }
    }

    private void requireActiveUser(AppUser actor) {
        if (actor == null || actor.getId() == null || !actor.isActive()) {
            throw new AuthorizationException("Sign-in is required.");
        }
    }

    private String generateUniqueInviteToken() {
        for (int attempt = 0; attempt < MAXIMUM_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String token = tokenService.generateToken();
            Long existingCount = entityManager
                    .createQuery(
                            "select count(invitation) from AppInvitation invitation "
                                    + "where invitation.inviteToken = :token",
                            Long.class)
                    .setParameter("token", token)
                    .getSingleResult();
            if (existingCount == 0) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite token.");
    }

    private boolean matchesBootstrapInviteToken(String inviteToken) {
        String configuredBootstrapInviteToken = normalizeInviteToken(bootstrapInviteToken);
        if (configuredBootstrapInviteToken.isBlank()) {
            return false;
        }

        return MessageDigest.isEqual(
                configuredBootstrapInviteToken.getBytes(StandardCharsets.UTF_8),
                inviteToken.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeInviteToken(String inviteToken) {
        if (inviteToken == null) {
            return "";
        }
        return inviteToken.trim();
    }

    private ValidationException invalidInvitationException() {
        return new ValidationException("Invitation is invalid or no longer available.");
    }
}
