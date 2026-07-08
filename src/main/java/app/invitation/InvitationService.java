package app.invitation;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.security.TokenService;
import app.user.AppUser;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Stateless
public class InvitationService {
    private static final int MAXIMUM_TOKEN_GENERATION_ATTEMPTS = 10;

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
    private AuditService auditService;

    public CalendarInvitation createInvitation(AppUser actor, Long calendarId, CalendarRole role, OffsetDateTime expiresAt) {
        calendarAccessService.requireCanAdminister(actor, calendarId);
        invitationPolicy.requireInvitableRole(role);

        Calendar calendar = calendarService.requireActiveCalendar(calendarId);
        CalendarInvitation invitation = new CalendarInvitation();
        invitation.setCalendar(calendar);
        invitation.setInviteToken(generateUniqueInviteToken());
        invitation.setRole(role);
        invitation.setCreatedByUser(actor);
        invitation.setExpiresAt(expiresAt);
        invitation.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entityManager.persist(invitation);
        entityManager.flush();
        auditService.record(actor, calendar, "calendar_invitation", invitation.getId(), "created", "Invitation created.");
        return invitation;
    }

    public void revokeInvitation(AppUser actor, Long invitationId) {
        CalendarInvitation invitation = requireInvitationForUpdate(invitationId);
        calendarAccessService.requireCanAdminister(actor, invitation.getCalendar().getId());
        if (invitation.getAcceptedAt() != null) {
            throw new ValidationException("Accepted invitations cannot be revoked.");
        }
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
            auditService.record(actor, invitation.getCalendar(), "calendar_invitation", invitation.getId(), "revoked", "Invitation revoked.");
        }
    }

    public CalendarInvitation acceptInvitation(String inviteToken, AppUser acceptingUser) {
        if (acceptingUser == null || acceptingUser.getId() == null || !acceptingUser.isActive()) {
            throw new ValidationException("An active user is required to accept an invitation.");
        }

        CalendarInvitation invitation = requireOpenInvitation(inviteToken);
        if (!invitation.getCalendar().isActive()) {
            throw new NotFoundException("Invitation was not found.");
        }

        CalendarRole acceptedRole = invitation.getRole();
        calendarMembershipService.grantMembershipFromAcceptedInvitation(invitation.getCalendar(), acceptingUser, acceptedRole);

        invitation.setAcceptedByUser(acceptingUser);
        invitation.setAcceptedAt(OffsetDateTime.now(ZoneOffset.UTC));
        auditService.record(acceptingUser, invitation.getCalendar(), "calendar_invitation", invitation.getId(), "accepted", "Invitation accepted.");
        return invitation;
    }

    private CalendarInvitation requireInvitationForUpdate(Long invitationId) {
        if (invitationId == null) {
            throw new NotFoundException("Invitation was not found.");
        }

        try {
            return entityManager
                    .createQuery(
                            "select calendarInvitation from CalendarInvitation calendarInvitation "
                                    + "join fetch calendarInvitation.calendar "
                                    + "where calendarInvitation.id = :invitationId",
                            CalendarInvitation.class)
                    .setParameter("invitationId", invitationId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new NotFoundException("Invitation was not found.");
        }
    }

    private CalendarInvitation requireOpenInvitation(String inviteToken) {
        CalendarInvitation invitation = findByTokenForUpdate(inviteToken);
        invitationPolicy.requireOpen(
                invitation.getRevokedAt(),
                invitation.getAcceptedAt(),
                invitation.getExpiresAt(),
                OffsetDateTime.now(ZoneOffset.UTC));
        return invitation;
    }

    private CalendarInvitation findByTokenForUpdate(String inviteToken) {
        if (inviteToken == null || inviteToken.isBlank()) {
            throw new NotFoundException("Invitation was not found.");
        }

        try {
            TypedQuery<CalendarInvitation> query = entityManager
                    .createQuery(
                            "select calendarInvitation from CalendarInvitation calendarInvitation "
                                    + "join fetch calendarInvitation.calendar "
                                    + "where calendarInvitation.inviteToken = :inviteToken",
                            CalendarInvitation.class)
                    .setParameter("inviteToken", inviteToken.trim());
            return query.setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        } catch (NoResultException exception) {
            throw new NotFoundException("Invitation was not found.");
        }
    }

    private String generateUniqueInviteToken() {
        for (int attempt = 0; attempt < MAXIMUM_TOKEN_GENERATION_ATTEMPTS; attempt++) {
            String token = tokenService.generateToken();
            Long existingCount = entityManager
                    .createQuery(
                            "select count(calendarInvitation) from CalendarInvitation calendarInvitation "
                                    + "where calendarInvitation.inviteToken = :token",
                            Long.class)
                    .setParameter("token", token)
                    .getSingleResult();
            if (existingCount == 0) {
                return token;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite token.");
    }
}
