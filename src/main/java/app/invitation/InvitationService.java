package app.invitation;

import app.audit.AuditService;
import app.calendar.Calendar;
import app.calendar.CalendarService;
import app.membership.CalendarAccessService;
import app.membership.CalendarMembershipService;
import app.membership.CalendarRole;
import app.membership.CalendarRolePolicy;
import app.security.TokenService;
import app.user.AppUser;
import app.util.NotFoundException;
import app.util.ValidationException;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;

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
    private CalendarRolePolicy calendarRolePolicy;

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
        invitation.setCreatedAt(OffsetDateTime.now());
        entityManager.persist(invitation);
        auditService.record(actor, calendar, "calendar_invitation", null, "created", "Invitation created.");
        return invitation;
    }

    public void revokeInvitation(AppUser actor, Long invitationId) {
        CalendarInvitation invitation = requireInvitation(invitationId);
        calendarAccessService.requireCanAdminister(actor, invitation.getCalendar().getId());
        if (invitation.getAcceptedAt() != null) {
            throw new ValidationException("Accepted invitations cannot be revoked.");
        }
        if (invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(OffsetDateTime.now());
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
        calendarMembershipService.findMembership(invitation.getCalendar().getId(), acceptingUser.getId())
                .ifPresentOrElse(existingMembership -> {
                    CalendarRole strongerRole = calendarRolePolicy.strongerRole(existingMembership.getRole(), acceptedRole);
                    existingMembership.setRole(strongerRole);
                    existingMembership.setActive(true);
                    existingMembership.setUpdatedAt(OffsetDateTime.now());
                }, () -> calendarMembershipService.grantOrUpdateMembership(invitation.getCalendar(), acceptingUser, acceptedRole));

        invitation.setAcceptedByUser(acceptingUser);
        invitation.setAcceptedAt(OffsetDateTime.now());
        auditService.record(acceptingUser, invitation.getCalendar(), "calendar_invitation", invitation.getId(), "accepted", "Invitation accepted.");
        return invitation;
    }

    private CalendarInvitation requireInvitation(Long invitationId) {
        CalendarInvitation invitation = entityManager.find(CalendarInvitation.class, invitationId);
        if (invitation == null) {
            throw new NotFoundException("Invitation was not found.");
        }
        return invitation;
    }

    private CalendarInvitation requireOpenInvitation(String inviteToken) {
        CalendarInvitation invitation = findByToken(inviteToken);
        invitationPolicy.requireOpen(
                invitation.getRevokedAt(), invitation.getAcceptedAt(), invitation.getExpiresAt(), OffsetDateTime.now());
        return invitation;
    }

    private CalendarInvitation findByToken(String inviteToken) {
        if (inviteToken == null || inviteToken.isBlank()) {
            throw new NotFoundException("Invitation was not found.");
        }

        try {
            return entityManager
                    .createQuery(
                            "select calendarInvitation from CalendarInvitation calendarInvitation "
                                    + "join fetch calendarInvitation.calendar "
                                    + "where calendarInvitation.inviteToken = :inviteToken",
                            CalendarInvitation.class)
                    .setParameter("inviteToken", inviteToken.trim())
                    .getSingleResult();
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
